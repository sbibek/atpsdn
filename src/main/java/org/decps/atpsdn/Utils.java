package org.decps.atpsdn;

public class Utils {
    public static long getUnsignedInt(int x) {
        return x & 0x00000000ffffffffL;
    }
}
