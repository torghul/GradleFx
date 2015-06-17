package org.sonatype.flexmojos.util;

public class OSUtils
{

    private static final String WINDOWS_CMD = "FlashPlayer.exe";

    private static final String MAC_CMD = "Flash Player";

    private static final String UNIX_CMD = "flashplayer";

    public enum OS
    {
        windows, linux, solaris, mac, unix, other;
    }

    public static OS getOSType()
    {
        String osName = System.getProperty( "os.name" ).toLowerCase();
        for ( OS os : OS.values() )
        {
            if ( osName.contains( os.toString() ) )
            {
                return os;
            }
        }
        return OS.other;
    }

    public static String[] getPlatformDefaultFlashPlayer()
    {
        if(isWindows()) {
            return [WINDOWS_CMD]
        } else if(isMacOS()) {
            return [MAC_CMD]
        }

        return [UNIX_CMD]
    }

    public static String[] getPlatformDefaultAdl()
    {
        if(isWindows()) {
            return [ "adl.exe"]
        }

        return [ "adl" ]
    }

    public static boolean isLinux()
    {
        if(isWindows() || isMacOS()) {
            return false
        }

        return true
    }

    public static boolean isWindows()
    {
        return getOSType().equals( OS.windows );
    }

    public static boolean isMacOS()
    {
        return getOSType().equals( OS.mac );
    }

}