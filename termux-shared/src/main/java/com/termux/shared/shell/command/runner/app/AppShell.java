package com.termux.shared.shell.command.runner.app;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Joiner;
import com.termux.shared.R;
import com.termux.shared.data.DataUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.result.ResultData;
import com.termux.shared.errors.Errno;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand.ExecutionState;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.StreamGobbler;

import com.termux.shared.termux.TermuxConstants;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * A class that maintains info for background app shells run with {@link Runtime#exec(String[], String[], File)}.
 * It also provides a way to link each {@link Process} with the {@link ExecutionCommand}
 * that started it. The shell is run in the app user context.
 */
public final class AppShell {

    private final Process mProcess;
    private final ExecutionCommand mExecutionCommand;
    private final AppShellClient mAppShellClient;

    private static final String LOG_TAG = "AppShell";

    private AppShell(@NonNull final Process process, @NonNull final ExecutionCommand executionCommand,
                     final AppShellClient appShellClient) {
        this.mProcess = process;
        this.mExecutionCommand = executionCommand;
        this.mAppShellClient = appShellClient;
    }

    /**
     * Start execution of an {@link ExecutionCommand} with {@link Runtime#exec(String[], String[], File)}.
     *
     * The {@link ExecutionCommand#executable}, must be set.
     * The  {@link ExecutionCommand#commandLabel}, {@link ExecutionCommand#arguments} and
     * {@link ExecutionCommand#workingDirectory} may optionally be set.
     *
     * @param currentPackageContext The {@link Context} for operations. This must be the context for
     *                              the current package and not the context of a `sharedUserId` package,
     *                              since environment setup may be dependent on current package.
     * @param executionCommand The {@link ExecutionCommand} containing the information for execution command.
     * @param appShellClient The {@link AppShellClient} interface implementation.
     *                           The {@link AppShellClient#onAppShellExited(AppShell)} will
     *                           be called regardless of {@code isSynchronous} value but not if
     *                           {@code null} is returned by this method. This can
     *                           optionally be {@code null}.
     * @param shellEnvironmentClient The {@link IShellEnvironment} interface implementation.
     * @param additionalEnvironment The additional shell environment variables to export. Existing
     *                              variables will be overridden.
     * @param isSynchronous If set to {@code true}, then the command will be executed in the
     *                      caller thread and results returned synchronously in the {@link ExecutionCommand}
     *                      sub object of the {@link AppShell} returned.
     *                      If set to {@code false}, then a new thread is started run the commands
     *                      asynchronously in the background and control is returned to the caller thread.
     * @return Returns the {@link AppShell}. This will be {@code null} if failed to start the execution command.
     */
    public static AppShell execute(@NonNull final Context currentPackageContext, @NonNull ExecutionCommand executionCommand,
                                   final AppShellClient appShellClient,
                                   @NonNull final IShellEnvironment shellEnvironmentClient,
                                   @Nullable HashMap<String, String> additionalEnvironment,
                                   final boolean isSynchronous) {
        if (executionCommand.executable == null || executionCommand.executable.isEmpty()) {
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(),
                currentPackageContext.getString(R.string.error_executable_unset, executionCommand.getCommandIdAndLabelLogString()));
            AppShell.processAppShellResult(null, executionCommand);
            return null;
        }

        if (executionCommand.workingDirectory == null || executionCommand.workingDirectory.isEmpty())
            executionCommand.workingDirectory = shellEnvironmentClient.getDefaultWorkingDirectoryPath();
        if (executionCommand.workingDirectory.isEmpty())
            executionCommand.workingDirectory = "/";

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        String executableBasename = ShellUtils.getExecutableBasename(executionCommand.executable);

        if (executionCommand.shellName == null)
            executionCommand.shellName = executableBasename;

        if (executionCommand.commandLabel == null)
            executionCommand.commandLabel = executableBasename;

        // Setup command args
        final String[] initialCommandArray = shellEnvironmentClient.setupShellCommandArguments(executionCommand.executable, executionCommand.arguments);

        // Setup command environment
        HashMap<String, String> environment = shellEnvironmentClient.setupShellCommandEnvironment(currentPackageContext,
            executionCommand);
        if (additionalEnvironment != null)
            environment.putAll(additionalEnvironment);

        final String[] commandArray;
        if (TermuxConstants.isSecondaryUser(currentPackageContext) && !executionCommand.isFailsafe) {
            String realFilesDir = TermuxConstants.getRealFilesDirPath(currentPackageContext);
            String realPrefixDir = TermuxConstants.getRealPrefixDirPath(currentPackageContext);
            String realHomeDir = TermuxConstants.getRealHomeDirPath(currentPackageContext);
            // Extract /data/user/N from /data/user/N/com.termux/files
            String realUserDataDir = new File(realFilesDir).getParentFile().getParent();
            String prootBinary = realPrefixDir + "/bin/proot";

            File homeDirFile = new File(realHomeDir);
            homeDirFile.mkdirs();
            try { android.system.Os.chmod(homeDirFile.getAbsolutePath(), 0700); } catch (Exception ignored) {}

            File tmpDirFile = new File(realPrefixDir + "/tmp");
            tmpDirFile.mkdirs();
            try { android.system.Os.chmod(tmpDirFile.getAbsolutePath(), 0700); } catch (Exception ignored) {}

            // Create apt config dirs to suppress "Unable to read" warnings
            new File(realPrefixDir + "/etc/apt/apt.conf.d").mkdirs();
            new File(realPrefixDir + "/etc/apt/preferences.d").mkdirs();

            if (new File(prootBinary).canExecute()) {
                List<String> prootArgsList = new ArrayList<>();
                prootArgsList.add(prootBinary);
                prootArgsList.add("--link2symlink");
                // Key binding: map /data/data (a symlink to /data/user/0) to /data/user/N.
                prootArgsList.add("-b");
                prootArgsList.add(realUserDataDir + ":/data/data!");
                prootArgsList.add("-b");
                prootArgsList.add("/dev");
                prootArgsList.add("-b");
                prootArgsList.add("/proc");
                prootArgsList.add("-b");
                prootArgsList.add("/system");
                prootArgsList.add("-b");
                prootArgsList.add("/apex");
                prootArgsList.add("-b");
                prootArgsList.add("/vendor");
                prootArgsList.add("-b");
                prootArgsList.add("/storage");
                prootArgsList.add("-w");
                prootArgsList.add(executionCommand.workingDirectory != null ? executionCommand.workingDirectory : "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/home");

                for (String arg : initialCommandArray) {
                    prootArgsList.add(arg);
                }
                commandArray = prootArgsList.toArray(new String[0]);
                executionCommand.workingDirectory = realFilesDir;

                environment.put("HOME", "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/home");
                environment.put("PREFIX", "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr");
                environment.put("PROOT_TMP_DIR", realPrefixDir + "/tmp");
                environment.put("PROOT_LOADER", realPrefixDir + "/libexec/proot/loader");
                environment.put("PROOT_LOADER_32", realPrefixDir + "/libexec/proot/loader32");
                environment.put("TMPDIR", "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/tmp");
                environment.put("PATH", "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin:/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin/applets:" + realPrefixDir + "/bin:/system/bin:/system/xbin");
                environment.put("LD_LIBRARY_PATH", realPrefixDir + "/lib:/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/lib");

                Long userId = com.termux.shared.android.PackageUtils.getUserIdForPackage(currentPackageContext);
                if (userId != null) {
                    environment.put("TERMUX__USER_ID", String.valueOf(userId));
                    environment.put("TERMUX_APP__USER_ID", String.valueOf(userId));
                }
            } else {
                commandArray = initialCommandArray;
            }
        } else {
            commandArray = initialCommandArray;
        }

        List<String> environmentList = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment);
        Collections.sort(environmentList);
        String[] environmentArray = environmentList.toArray(new String[0]);

        if (!executionCommand.setState(ExecutionState.EXECUTING)) {
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), currentPackageContext.getString(R.string.error_failed_to_execute_app_shell_command, executionCommand.getCommandIdAndLabelLogString()));
            AppShell.processAppShellResult(null, executionCommand);
            return null;
        }

        // No need to log stdin if logging is disabled, like for app internal scripts
        Logger.logDebugExtended(LOG_TAG, ExecutionCommand.getExecutionInputLogString(executionCommand,
            true, Logger.shouldEnableLoggingForCustomLogLevel(executionCommand.backgroundCustomLogLevel)));
        Logger.logVerboseExtended(LOG_TAG, "\"" + executionCommand.getCommandIdAndLabelLogString() + "\" AppShell Environment:\n" +
            Joiner.on("\n").join(environmentArray));

        // Exec the process
        final Process process;
        try {
            process = Runtime.getRuntime().exec(commandArray, environmentArray, new File(executionCommand.workingDirectory));
        } catch (IOException e) {
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), currentPackageContext.getString(R.string.error_failed_to_execute_app_shell_command, executionCommand.getCommandIdAndLabelLogString()), e);
            AppShell.processAppShellResult(null, executionCommand);
            return null;
        }

        final AppShell appShell = new AppShell(process, executionCommand, appShellClient);
        if (isSynchronous) {
            try {
                appShell.executeInner(currentPackageContext);
            } catch (IllegalThreadStateException | InterruptedException e) {
                // TODO: Should either of these be handled or returned?
            }
        } else {
            new Thread() {
                @Override
                public void run() {
                    try {
                        appShell.executeInner(currentPackageContext);
                    } catch (IllegalThreadStateException | InterruptedException e) {
                        // TODO: Should either of these be handled or returned?
                    }
                }
            }.start();
        }

        return appShell;
    }

    /**
     * Sets up stdout and stderr readers for the {@link #mProcess} and waits for the process to end.
     *
     * If the processes finishes, then sets {@link ResultData#stdout}, {@link ResultData#stderr}
     * and {@link ResultData#exitCode} for the {@link #mExecutionCommand} of the {@code appShell}
     * and then calls {@link #processAppShellResult(AppShell, ExecutionCommand) to process the result}.
     *
     * @param context The {@link Context} for operations.
     */
    private void executeInner(@NonNull final Context context) throws IllegalThreadStateException, InterruptedException {
        mExecutionCommand.mPid = ShellUtils.getPid(mProcess);

        Logger.logDebug(LOG_TAG, "Running \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" AppShell with pid " + mExecutionCommand.mPid);

        mExecutionCommand.resultData.exitCode = null;

        // setup stdin, and stdout and stderr gobblers
        DataOutputStream STDIN = new DataOutputStream(mProcess.getOutputStream());
        StreamGobbler STDOUT = new StreamGobbler(mExecutionCommand.mPid + "-stdout", mProcess.getInputStream(), mExecutionCommand.resultData.stdout, mExecutionCommand.backgroundCustomLogLevel);
        StreamGobbler STDERR = new StreamGobbler(mExecutionCommand.mPid + "-stderr", mProcess.getErrorStream(), mExecutionCommand.resultData.stderr, mExecutionCommand.backgroundCustomLogLevel);

        // start gobbling
        STDOUT.start();
        STDERR.start();

        if (!DataUtils.isNullOrEmpty(mExecutionCommand.stdin)) {
            try {
                STDIN.write((mExecutionCommand.stdin + "\n").getBytes(StandardCharsets.UTF_8));
                STDIN.flush();
                STDIN.close();
                //STDIN.write("exit\n".getBytes(StandardCharsets.UTF_8));
                //STDIN.flush();
            } catch(IOException e) {
                if (e.getMessage() != null && (e.getMessage().contains("EPIPE") || e.getMessage().contains("Stream closed"))) {
                    // Method most horrid to catch broken pipe, in which case we
                    // do nothing. The command is not a shell, the shell closed
                    // STDIN, the script already contained the exit command, etc.
                    // these cases we want the output instead of returning null.
                } else {
                    // other issues we don't know how to handle, leads to
                    // returning null
                    mExecutionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_exception_received_while_executing_app_shell_command, mExecutionCommand.getCommandIdAndLabelLogString(), e.getMessage()), e);
                    mExecutionCommand.resultData.exitCode = 1;
                    AppShell.processAppShellResult(this, null);
                    kill();
                    return;
                }
            }
        }

        // wait for our process to finish, while we gobble away in the background
        int exitCode = mProcess.waitFor();

        // make sure our threads are done gobbling
        // and the process is destroyed - while the latter shouldn't be
        // needed in theory, and may even produce warnings, in "normal" Java
        // they are required for guaranteed cleanup of resources, so lets be
        // safe and do this on Android as well
        try {
            STDIN.close();
        } catch (IOException e) {
            // might be closed already
        }
        STDOUT.join();
        STDERR.join();
        mProcess.destroy();

        // Process result
        if (exitCode == 0)
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" AppShell with pid " + mExecutionCommand.mPid + " exited normally");
        else
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" AppShell with pid " + mExecutionCommand.mPid + " exited with code: " + exitCode);

        // If the execution command has already failed, like SIGKILL was sent, then don't continue
        if (mExecutionCommand.isStateFailed()) {
            Logger.logDebug(LOG_TAG, "Ignoring setting \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" AppShell state to ExecutionState.EXECUTED and processing results since it has already failed");
            return;
        }

        mExecutionCommand.resultData.exitCode = exitCode;

        if (!mExecutionCommand.setState(ExecutionState.EXECUTED))
            return;

        AppShell.processAppShellResult(this, null);
    }

    /**
     * Kill this {@link AppShell} by sending a {@link OsConstants#SIGILL} to its {@link #mProcess}
     * if its still executing.
     *
     * @param context The {@link Context} for operations.
     * @param processResult If set to {@code true}, then the {@link #processAppShellResult(AppShell, ExecutionCommand)}
     *                      will be called to process the failure.
     */
    public void killIfExecuting(@NonNull final Context context, boolean processResult) {
        // If execution command has already finished executing, then no need to process results or send SIGKILL
        if (mExecutionCommand.hasExecuted()) {
            Logger.logDebug(LOG_TAG, "Ignoring sending SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" AppShell since it has already finished executing");
            return;
        }

        Logger.logDebug(LOG_TAG, "Send SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" AppShell");

        if (mExecutionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                mExecutionCommand.resultData.exitCode = 137; // SIGKILL
                AppShell.processAppShellResult(this, null);
            }
        }

        if (mExecutionCommand.isExecuting()) {
            kill();
        }
    }

    /**
     * Kill this {@link AppShell} by sending a {@link OsConstants#SIGILL} to its {@link #mProcess}.
     */
    public void kill() {
        int pid = ShellUtils.getPid(mProcess);
        try {
            // Send SIGKILL to process
            Os.kill(pid, OsConstants.SIGKILL);
        } catch (ErrnoException e) {
            Logger.logWarn(LOG_TAG, "Failed to send SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" AppShell with pid " + pid + ": " + e.getMessage());
        }
    }

    /**
     * Process the results of {@link AppShell} or {@link ExecutionCommand}.
     *
     * Only one of {@code appShell} and {@code executionCommand} must be set.
     *
     * If the {@code appShell} and its {@link #mAppShellClient} are not {@code null},
     * then the {@link AppShellClient#onAppShellExited(AppShell)} callback will be called.
     *
     * @param appShell The {@link AppShell}, which should be set if
     *                  {@link #execute(Context, ExecutionCommand, AppShellClient, IShellEnvironment, HashMap, boolean)}
     *                   successfully started the process.
     * @param executionCommand The {@link ExecutionCommand}, which should be set if
     *                          {@link #execute(Context, ExecutionCommand, AppShellClient, IShellEnvironment, HashMap, boolean)}
     *                          failed to start the process.
     */
    private static void processAppShellResult(final AppShell appShell, ExecutionCommand executionCommand) {
        if (appShell != null)
            executionCommand = appShell.mExecutionCommand;

        if (executionCommand == null) return;

        if (executionCommand.shouldNotProcessResults()) {
            Logger.logDebug(LOG_TAG, "Ignoring duplicate call to process \"" + executionCommand.getCommandIdAndLabelLogString() + "\" AppShell result");
            return;
        }

        Logger.logDebug(LOG_TAG, "Processing \"" + executionCommand.getCommandIdAndLabelLogString() + "\" AppShell result");

        if (appShell != null && appShell.mAppShellClient != null) {
            appShell.mAppShellClient.onAppShellExited(appShell);
        } else {
            // If a callback is not set and execution command didn't fail, then we set success state now
            // Otherwise, the callback host can set it himself when its done with the appShell
            if (!executionCommand.isStateFailed())
                executionCommand.setState(ExecutionCommand.ExecutionState.SUCCESS);
        }
    }

    public Process getProcess() {
        return mProcess;
    }

    public ExecutionCommand getExecutionCommand() {
        return mExecutionCommand;
    }



    public interface AppShellClient {

        /**
         * Callback function for when {@link AppShell} exits.
         *
         * @param appShell The {@link AppShell} that exited.
         */
        void onAppShellExited(AppShell appShell);

    }

}
