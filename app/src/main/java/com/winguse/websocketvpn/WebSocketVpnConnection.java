package com.winguse.websocketvpn;

import android.app.PendingIntent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WebSocketVpnConnection implements Runnable {
    public WebSocketVpnConnection(VpnService mService, int mConnectionId, String mServerUrl) {
        this.mService = mService;
        this.mConnectionId = mConnectionId;
        this.mServerUrl = mServerUrl;
    }

    public enum ConnectionState {
        Connecting,
        Error,
        Connected,
        Established,
        Closing,
        Closed,
    }

    /**
     * Maximum packet size is constrained by the MTU, which is given as a signed short.
     */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

    /**
     * Callback interface to let the {@link WebSocketVpnService} know about new connections
     * and update the foreground notification with connection status.
     */
    public interface OnEstablishListener {
        void onEstablish(ParcelFileDescriptor tunInterface);
    }

    private PendingIntent mConfigureIntent;
    private OnEstablishListener mOnEstablishListener;
    private final VpnService mService;
    private final int mConnectionId;
    private final String mServerUrl;

    /**
     * Optionally, set an intent to configure the VPN. This is {@code null} by default.
     */
    public void setConfigureIntent(PendingIntent intent) {
        mConfigureIntent = intent;
    }


    public void setOnEstablishListener(OnEstablishListener listener) {
        mOnEstablishListener = listener;
    }


    @Override
    public void run() {
        try {
            Log.i(getTag(), "Starting");
            // We try to create the tunnel several times.
            // TODO: The better way is to work with ConnectivityManager, trying only when the
            // network is available.
            // Here we just use a counter to keep things simple.
            for (int attempt = 0; attempt < 1; ++attempt) {
                // Reset the counter if we were connected.
                if (establish()) {
                    attempt = 0;
                }

                // Sleep for a while. This also checks if we got interrupted.
                Thread.sleep(3000);
            }
            Log.i(getTag(), "Giving up");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            Log.e(getTag(), "Connection failed, exiting", e);
        }
    }

    private boolean establish() throws IOException, InterruptedException, IllegalArgumentException {
        final ParcelFileDescriptor[] vpnInterface = {null};
        final ConnectionState[] state = {ConnectionState.Connecting};
        WebSocket webSocket = null;
        boolean stopByInterrupt = false;
        try {
            // Packets to be sent are queued in this input stream.
            final FileInputStream[] in = {null};
            // build and connect websocket
            OkHttpClient client = new OkHttpClient.Builder().build();
            webSocket = client.newWebSocket(new Request.Builder().url(mServerUrl).build(), new WebSocketListener() {
                private FileOutputStream out = null;
                @Override
                public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                    Log.i(getTag(), "web socket closing");
                    state[0] = ConnectionState.Closing;
                }

                @Override
                public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                    Log.e(getTag(), "Error from web socket listener", t);
                    state[0] = ConnectionState.Error;
                }

                @Override
                public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
                    if (state[0] != ConnectionState.Established) return;
                    try {
                        out.write(bytes.toByteArray());
                    } catch (IOException e) {
                        Log.e(getTag(), "failed to write data to web socket", e);
                        state[0] = ConnectionState.Error;
                    }
                }

                @Override
                public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                    state[0] = ConnectionState.Connected;
                    Log.i(getTag(), "web socket connected");
                }

                @Override
                public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                    if (state[0] == ConnectionState.Connected) {
                        try {
                            VpnService.Builder builder = mService
                                    .new Builder();
                            String[] slices = text.split(";", Integer.MAX_VALUE);
                            int mtu = Integer.parseInt(slices[0]);
                            builder.setMtu(mtu);
                            String[] ipListSlices = slices[1].split(",", Integer.MAX_VALUE);
                            for (String ipListSlice : ipListSlices) {
                                if (ipListSlice.isEmpty()) continue;
                                String[] ipSlices = ipListSlice.split("/", Integer.MAX_VALUE);
                                String ip = ipSlices[0];
                                int prefixLength = Integer.parseInt(ipSlices[1]);
                                builder.addAddress(ip, prefixLength);
                            }
                            String[] routeListSlices = slices[2].split(",", Integer.MAX_VALUE);
                            for (String routeListSlice : routeListSlices) {
                                if (routeListSlice.isEmpty()) continue;
                                String[] ipSlices = routeListSlice.split("/", Integer.MAX_VALUE);
                                String ip = ipSlices[0];
                                int prefixLength = Integer.parseInt(ipSlices[1]);
                                builder.addRoute(ip, prefixLength);
                            }
                            builder.addRoute("0.0.0.0", 0);
                            builder.addDnsServer("8.8.8.8");
                            builder.addDisallowedApplication("com.winguse.websocketvpn");
                            builder.setConfigureIntent(mConfigureIntent);
                            synchronized (mService) {
                                vpnInterface[0] = builder.establish();
                            }
                            if (mOnEstablishListener != null) {
                                mOnEstablishListener.onEstablish(vpnInterface[0]);
                                Log.i(getTag(), "New interface: " + vpnInterface[0]);
                                out = new FileOutputStream(vpnInterface[0].getFileDescriptor());
                                in[0] = new FileInputStream(vpnInterface[0].getFileDescriptor());
                                state[0] = ConnectionState.Established;
                            } else {
                                Log.w(getTag(), "failed to build establish, null dev returned.");
                                state[0] = ConnectionState.Error;
                            }
                        } catch (Exception e) {
                            Log.e(getTag(), "failed to parse remote config: " + text, e);
                            state[0] = ConnectionState.Error;
                        }
                    } else {
                        Log.w(getTag(), "unexpected text message from upstream: " + text + ", current state: " + state[0]);
                    }
                }

                @Override
                public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                    state[0] = ConnectionState.Closed;
                    Log.i(getTag(), "web socket closed");
                }
            });

            while(state[0] == ConnectionState.Connecting || state[0] == ConnectionState.Connected) {
                Thread.sleep(100);
            }

            // copy from local vpn to websocket
            byte[] data = new byte[MAX_PACKET_SIZE];
            while (state[0] == ConnectionState.Established) {
                StructPollfd deviceFd = new StructPollfd();
                deviceFd.fd = in[0].getFD();
                deviceFd.events = (short) (OsConstants.POLLIN);
                Os.poll(new StructPollfd[]{deviceFd}, 100);
                if ((deviceFd.events & OsConstants.POLLIN) != 0) {
                    int length = in[0].read(data);
                    if (length > 0) {
                        webSocket.send(ByteString.of(data, 0, length));
                    }
                }
                if ((deviceFd.events & OsConstants.POLLHUP) != 0) {
                    Log.i(getTag(), "get POLLHUP, exit loop");
                    state[0] = ConnectionState.Closing;
                }
                if ((deviceFd.events & OsConstants.POLLERR) != 0) {
                    Log.i(getTag(), "get POLLERR, exit loop");
                    state[0] = ConnectionState.Error;
                }
            }
            Log.i(getTag(), "web socket main loop exit, current state of the web socket: " + state[0]);
        } catch(InterruptedException e){
            Log.w(getTag(), "thread get interrupted", e);
            stopByInterrupt = true;
        } catch (Exception e) {
            Log.e(getTag(), "Error in VPN connection", e);
        } finally {
            if (vpnInterface[0] != null) {
                try {
                    vpnInterface[0].close();
                } catch (IOException e) {
                    Log.e(getTag(), "Unable to close interface", e);
                }
            }
            if (webSocket != null) {
                try {
                    webSocket.close(1000, null);
                } catch (Exception e) {
                    Log.e(getTag(), "failed to close web socket");
                }
            }
        }
        Log.i(getTag(), "web socket func terminated with state: " + state[0]);
        return stopByInterrupt || state[0] == ConnectionState.Closed;
    }

    private final String getTag() {
        return WebSocketVpnConnection.class.getSimpleName() + "[" + mConnectionId + "]";
    }
}
