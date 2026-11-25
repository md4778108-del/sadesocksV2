package hev.htproxy;

/**
 * JNI binding for hev-socks5-tunnel 2.17+ (Start/Stop return boolean, IsRunning added).
 * The native library registers its methods on exactly this class name and these exact
 * signatures, so do not rename, move or change them.
 */
public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
