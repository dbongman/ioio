package ioio.lib.pic;

import ioio.lib.IoioException.ConnectionLostException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * Socket based connection to
 *
 */
public class IoioConnection implements ConnectionStateCallback {
	IncomingHandler incomingHandler;
	private final ListenerManager listeners;

	// TODO(arshan): remove this.
	public static final int IOIO_TIMEOUT = 3000;

	public static final byte EOF = -1;

	ServerSocket ssocket;
	Socket socket;
	int port;

	InputStream in;
	OutputStream out;

	// Connection state.
	int state = DISCONNECTED;

	public static final int DISCONNECTED = 3;
	public static final int VERIFIED = 2;

	private boolean disconnectionRequested;
    private PacketFramerRegistry framerRegistry;

	public IoioConnection(ListenerManager listener) {
		this(Constants.IOIO_PORT, listener);
	}

	public IoioConnection(int port, ListenerManager listeners) {
		this.port = port;
		this.listeners = listeners;
	}

	IoioConnection(InputStream in, OutputStream out, ListenerManager listeners) {
		this.in = in;
		this.out = out;
		this.listeners = listeners;
	}

    IoioConnection(ServerSocket ssocket, ListenerManager listeners) {
		this.ssocket = ssocket;
		this.listeners = listeners;
	}

	private boolean bindOnPortForIOIOBoard() throws IOException, BindException {
	    if (ssocket == null) {
	        IoioLogger.log("binding on port : " + port);
	        ssocket = new ServerSocket(port);
	        setTimeout(IOIO_TIMEOUT);
	    }
		return true;
	}

    private void handleShutdown() throws IOException {
        IoioLogger.log("Connection is shutting down");
        listeners.disconnectListeners();
        if (incomingHandler != null) {
            incomingHandler.halt();
            safeJoin(incomingHandler);
            incomingHandler = null;
        }

        framerRegistry.reset();

        if (in != null) {
            in.close();
            in = null;
        }

        if (out != null) {
            out.close();
        }

        if (socket != null) {
            socket.close();
            socket = null;
        }
        IoioLogger.log("shutdown complete");
    }

	private void safeJoin(Thread thread) {
	    if (Thread.currentThread() != thread && thread != null) {
	        try {
                thread.join();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                IoioLogger.log("Yikes! error in join");
            }
	    }
    }

    private void setTimeout(int timeout) {
		if (ssocket != null && timeout > 0) {
			try {
				ssocket.setSoTimeout(timeout);
			} catch (SocketException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Attempt to reconnect ( or connect if first time ).
	 *
	 * @return true if successful
	 */
	public boolean waitForBoardToConnect() {
	    IoioLogger.log("waiting for connection...");
		try {
			if (socket != null) {
				socket.close();
				socket = null;
			}
			socket = ssocket.accept();
		} catch (IOException e) {
		    return false;
		}
		return true;
	}

	public boolean isConnectedEstablished() {
		return state == VERIFIED;
	}

	public void sendToIOIO(IoioPacket packet) throws ConnectionLostException {
	    packet.log(">> ");
        try {
            out.write(packet.message);
            if (packet.payload != null) {
                out.write(packet.payload);
            }
        } catch (IOException e) {
            throw new ConnectionLostException("IO Exception sending packet: " + e.getMessage());
        }
	}

    public void disconnect() {
        disconnectionRequested = true;
        try {
            handleShutdown();
            if (ssocket != null) {
                ssocket.close();
                ssocket = null;
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void join() throws InterruptedException {
        IoioLogger.log("Waiting for threads to join");

        if (incomingHandler != null) {
            incomingHandler.join();
            IoioLogger.log("Incoming handler joined");
            incomingHandler = null;
        }
    }

    public void start(PacketFramerRegistry framerRegistry) throws BindException, IOException {
        this.framerRegistry = framerRegistry;
        listeners.disconnectListeners();
        disconnectionRequested = false;
        bindOnPortForIOIOBoard();
        while (!disconnectionRequested && !waitForBoardToConnect());
        if (disconnectionRequested) {
            return;
        }
        IoioLogger.log("initial connection");
        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
        framerRegistry.registerFramer(Constants.ESTABLISH_CONNECTION, ESTABLISH_CONNECTION_FRAMER);
        framerRegistry.registerFramer(Constants.SOFT_RESET, SOFT_RESET_OR_EOF);
        framerRegistry.registerFramer(IoioConnection.EOF, SOFT_RESET_OR_EOF);
        incomingHandler = new IncomingHandler(in, this, listeners, framerRegistry);
        incomingHandler.start();
    }

    @Override
    public void stateChanged(ConnectionState state) {
        IoioLogger.log("State changed to " + state);
        if (ConnectionState.CONNECTED.equals(state)) {
            this.state = VERIFIED;
        }

        if (ConnectionState.SHUTTING_DOWN.equals(state)) {
            this.state = DISCONNECTED;
            try {
                handleShutdown();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isVerified() {
        return state == VERIFIED;
    }

    private PacketFramer SOFT_RESET_OR_EOF =
        new PacketFramer() {
            @Override
            public IoioPacket frame(byte message, InputStream in) throws IOException {
                if (message == IoioConnection.EOF) {
                    IoioLogger.log("Connection broken by EOF");
//                    stateChanged(ConnectionState.SHUTTING_DOWN);
                    return null;
                }
                assert(message == Constants.SOFT_RESET);
                return new IoioPacket(message, null);
            }
        };

    private PacketFramer ESTABLISH_CONNECTION_FRAMER =
        new PacketFramer() {
            @Override
            public IoioPacket frame(byte message, InputStream in) throws IOException {
                assert(message == Constants.ESTABLISH_CONNECTION);
                IoioPacket packet = new IoioPacket(message, Bytes.readBytes(in, 13));
                if (IncomingHandler.verifyEstablishPacket(packet)) {
                    stateChanged(ConnectionState.CONNECTED);
                    return packet;
                } else {
                    stateChanged(ConnectionState.SHUTTING_DOWN);
                }
                return null;
            }
        };
}