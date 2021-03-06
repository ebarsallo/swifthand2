package smarthand.ui_explorer;

import smarthand.ui_explorer.util.SubProcess;
import smarthand.ui_explorer.util.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * A singleton total manager for Monkey socket interface.
 *
 * @author  Wenyu Wang
 * @version 1.0
 * @since   2016-05-26
 */
public class MonkeyManager {

    private static SubProcess mProcess = null;
    private static String mAdbCommand = "adb";
    private static String mDeviceName = "";
    private static int mPort = 0;
    private static Socket mMonkeySocket = null;
    private static BufferedReader mFromMonkey = null;
    private static PrintWriter mToMonkey = null;
    private static final String CMD_MONKEY = "monkey";

    public static boolean isRunning() {
        return mProcess != null && mProcess.isAlive();
    }

    public static void start(final String sAdbCommand,
                             final String sDeviceName,
                             final String sTargetPackage,
                             final String sLogFilename,
                             final int nPort) {
        if (isRunning())
            return;

        String cmd;

        // Monkey only listens on localhost, so we have to do port forwarding.
        cmd = String.format("%s -s %s forward tcp:%d tcp:%d",
                sAdbCommand, sDeviceName, nPort, nPort);
        Util.executeShellCommand(cmd, null);

        // Force kill any running Monkey.
        SubProcess.forceKill(sAdbCommand, sDeviceName, CMD_MONKEY);

        // Launch Monkey.
        cmd = String.format("%s -s %s shell %s -v -p %s --port %d",
                sAdbCommand, sDeviceName, CMD_MONKEY, sTargetPackage, nPort);
        mProcess = SubProcess.execCommand(cmd, sLogFilename, true);

        mAdbCommand = sAdbCommand;
        mDeviceName = sDeviceName;
        mPort = nPort;

        // Try connecting to Monkey
        int nRetry = 5;
        while (--nRetry >= 0) {
            try {
                Thread.sleep(500);
                mMonkeySocket = new Socket("127.0.0.1", nPort);
                mFromMonkey = new BufferedReader(new InputStreamReader(mMonkeySocket.getInputStream()));
                mToMonkey = new PrintWriter(mMonkeySocket.getOutputStream(), true);
                if (sendCommandToMonkey("listvar") == null)
                    throw new Exception("not connected.");
                return;
            } catch (Exception e) {
                mMonkeySocket = null;
                // e.printStackTrace();
                System.err.println("\tCannot connect to Monkey using port " + nPort + ", error msg: " + e.getMessage());
            }
        }

        System.err.println("CANNOT START MONKEY CORRECTLY");
        stop();
    }

    public static void sendCommandToMonkeyNoRet(String sCommand) {
        if (!isRunning())
            return;

        System.out.println("Sending to monkey via socket: " + sCommand);
        mToMonkey.println(sCommand);
    }

    public static String sendCommandToMonkey(String sCommand) {
        if (!isRunning())
            return null;

        System.out.println("Sending to monkey via socket: " + sCommand);
        mToMonkey.println(sCommand);
        // System.out.println("Sent, waiting for reply.");

        try {
            String ret = mFromMonkey.readLine();
            System.out.println("Monkey returned: " + ret);
            return ret;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static void sendBatchCommandsToMonkeyAndWaitToStop(String sCommands) {
        if (!isRunning())
            return;

        for (String cmd : sCommands.split("\n"))
            sendCommandToMonkeyNoRet(cmd);

        sendCommandToMonkeyNoRet("quit");
        System.out.println("Batch commands sent.");
        mProcess.join();
    }

    public static void stop() {
        if (!isRunning())
            return;

        String cmd;

        // Close socket first.
        if (mMonkeySocket != null) {
            try {
                mMonkeySocket.close();
            } catch (IOException e) {
                // ignore
            }
            mMonkeySocket = null;
        }

        // Stop Monkey right now.
        mProcess.kill();
        mProcess = null;

        // Make sure it stops.
        SubProcess.forceKill(mAdbCommand, mDeviceName, CMD_MONKEY);

        // Remove adb port forwarding.
        cmd = String.format("%s -s %s forward --remove tcp:%d",
                mAdbCommand, mDeviceName, mPort);
        Util.executeShellCommand(cmd, null);
    }

    private static int monkeyCounter = 0;

    /**
     * Run a monkey using a random mode. A random mode monkey can be used with UIAutomator.
     * The function stores the output generated by monkey (stdou) to a linked list of string,
     * if passed to buffer parameter.
     *
     * @param timeout           timeout in milliseconds
     * @param targetPackage     target package
     */
    public static void runRandomMonkey(long timeout, String targetPackage, String logFileName) {
        long throttle = 10; //millisecond
        long eventCount = timeout / throttle;
        String cmd = "adb -s "
                + Options.get(Options.Keys.DEVICE_NAME)
                + " shell " + CMD_MONKEY + " -p " + targetPackage
                + " --throttle "
                + throttle
                + " -s " + (monkeyCounter++)
                + " -v "
                + eventCount;

        if (monkeyCounter < 0) monkeyCounter = 0;

        System.out.println("Executing shell command: " + cmd);
        SubProcess proc = SubProcess.execCommand(cmd, logFileName, true);
        proc.join();
        System.out.println("Execution finished");
    }
}
