package android.util;
public final class Base64 {
    public static final int NO_WRAP=2;
    public static String encodeToString(byte[] data,int offset,int length,int flags){
        return java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(data,offset,offset+length));
    }
}
