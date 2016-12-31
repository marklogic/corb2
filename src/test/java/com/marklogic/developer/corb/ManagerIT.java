/*
 * Copyright (c) 2004-2017 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The use of the Apache License does not indicate that this project is
 * affiliated with the Apache Software Foundation.
 */
package com.marklogic.developer.corb;

import com.marklogic.developer.TestHandler;
import static com.marklogic.developer.corb.TestUtils.clearFile;
import static com.marklogic.developer.corb.TestUtils.clearSystemProperties;
import static com.marklogic.developer.corb.util.FileUtilsTest.getBytes;
import static com.marklogic.developer.corb.TestUtils.containsLogRecord;
import com.marklogic.developer.corb.util.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;

/**
 *
 * @author Mads Hansen, MarkLogic Corporation
 */
public class ManagerIT {

    @Rule
    public final ExpectedSystemExit exit = ExpectedSystemExit.none();
    public static final String SLASH = "/";
    private final TestHandler testLogger = new TestHandler();
    private static final Logger MANAGER_LOG = Logger.getLogger(Manager.class.getName());
    private static final Logger LOG = Logger.getLogger(ManagerIT.class.getName());
    private static final String EXT_TXT = ".txt";
    private static final String TRANSFORM_SLOW_MODULE = "src/test/resources/transformSlow.xqy|ADHOC";
    private static final String SLOW_CMD = "pause";
    private static final LogRecord PAUSING = new LogRecord(Level.INFO, "pausing");
    private static final LogRecord RESUMING = new LogRecord(Level.INFO, "resuming");
    private static final String CORB_INIT_ERROR_MSG = "Error initializing CORB";
    private static final String EXPECTED_OUTPUT = "This is being returned from the PRE-BATCH-MODULE which is often used for column headers.\nThis is a file generated by the XQUERY-MODULE (Transform) which typically contains a report.  This information [The Selector sends its greetings!  The COLLECTION-NAME is StringPassedToTheURIsModule] was passed from the Selector.\nThis is from the POST-BATCH-MODULE using the POST-XQUERY-MODULE.";
    private static final String POST_XQUERY_MODULE_OUTPUT = "This is from the POST-BATCH-MODULE using the POST-XQUERY-MODULE.";

    @Before
    public void setUp() throws IOException {
        clearSystemProperties();
        MANAGER_LOG.addHandler(testLogger);
        File tempDir = TestUtils.createTempDirectory();
        ManagerTest.EXPORT_FILE_DIR = tempDir.toString();
    }

    @After
    public void tearDown() throws IOException {
        FileUtils.deleteFile(ManagerTest.EXPORT_FILE_DIR);
        clearSystemProperties();
    }

    @Test
    public void testManagerUsingProgArgs() {

        clearSystemProperties();
        String exportFileName = "testManagerUsingProgArgs.txt";
        String exportFileDir = ManagerTest.EXPORT_FILE_DIR;
        String[] args = ManagerTest.getDefaultArgs();
        args[14] = exportFileName;
        args[15] = null;
        File report = new File(exportFileDir + SLASH + exportFileName);
        report.deleteOnExit();
        Manager manager = new Manager();
        try {
            //First, verify the output using run()
            manager.init(args);
            manager.run();

            byte[] out = getBytes(report);
            String corbOutput = new String(out).trim();
            boolean passed = EXPECTED_OUTPUT.equals(corbOutput);
            clearFile(report);
            assertTrue(passed);
            //Then verify the exit code when invoking the main()
            exit.expectSystemExitWithStatus(Manager.EXIT_CODE_SUCCESS);
            Manager.main(args);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
    }

    @Test
    public void testManagerUsingSysProps() {
        clearSystemProperties();
        String exportFileName = "testManagerUsingSysProps2.txt";
        ManagerTest.setDefaultSystemProperties();
        System.setProperty(Options.URIS_MODULE, "src/test/resources/selector.xqy|ADHOC");
        System.setProperty(Options.EXPORT_FILE_NAME, exportFileName);

        File report = new File(ManagerTest.EXPORT_FILE_DIR + SLASH + exportFileName);
        report.deleteOnExit();

        Manager manager = new Manager();
        String[] args = {};
        try {
            //First, verify the output by executing run()
            manager.init(args);
            manager.run();

            byte[] out = getBytes(report);
            String corbOutput = new String(out).trim();
            boolean passed = EXPECTED_OUTPUT.equals(corbOutput);
            clearFile(report);
            assertTrue(passed);
            //Then verify the exit code when using the main() method
            exit.expectSystemExitWithStatus(Manager.EXIT_CODE_SUCCESS);
            Manager.main(args);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
    }

    @Test
    public void testManagerUsingSysPropsLargeUrisList() {
        clearSystemProperties();
        int uriCount = 100;
        String exportFilename = "testManagerUsingSysProps1.txt";
        Properties properties = ManagerTest.getDefaultProperties();
        properties.setProperty(Options.THREAD_COUNT, "4");
        properties.setProperty(Options.URIS_MODULE, "src/test/resources/selectorLargeList.xqy|ADHOC");
        properties.setProperty(Options.URIS_MODULE + ".count", String.valueOf(uriCount));
        properties.setProperty(Options.EXPORT_FILE_NAME, exportFilename);
        properties.setProperty(Options.DISK_QUEUE_MAX_IN_MEMORY_SIZE, String.valueOf(10));
        properties.setProperty(Options.DISK_QUEUE_TEMP_DIR, "/var/tmp");

        Manager manager = new Manager();
        try {
            manager.init(properties);
            manager.run();
            File report = new File(ManagerTest.EXPORT_FILE_DIR + SLASH + exportFilename);
            report.deleteOnExit();
            int lineCount = FileUtils.getLineCount(report);
            assertEquals(uriCount + 2, lineCount);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
    }

    @Test
    public void testManagerUsingPropsFile() {
        String exportFileName = ManagerTest.EXPORT_FILE_DIR + SLASH + "testManagerUsingPropsFile.txt";
        clearSystemProperties();
        System.setProperty(Options.OPTIONS_FILE, "src/test/resources/helloWorld.properties");
        System.setProperty(Options.EXPORT_FILE_NAME, exportFileName);
        String[] args = {};
        //First, verify the output using run()
        Manager manager = new Manager();
        try {
            manager.init(args);
            manager.run();

            File report = new File(exportFileName);
            report.deleteOnExit();

            assertTrue(report.exists());

            byte[] out = getBytes(report);
            String corbOutput = new String(out).trim();
            boolean passed = EXPECTED_OUTPUT.equals(corbOutput);
            clearFile(report);

            assertTrue(passed);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        //Then verify the exit code when using the main() method
        exit.expectSystemExitWithStatus(Manager.EXIT_CODE_SUCCESS);
        Manager.main(args);
    }

    @Test
    public void testManagerUsingInputFile() {
        clearSystemProperties();
        String exportFileName = "testManagerUsingInputFile.txt";
        ManagerTest.setDefaultSystemProperties();
        System.setProperty(Options.EXPORT_FILE_NAME, exportFileName);
        System.setProperty(Options.URIS_FILE, "src/test/resources/uriInputFile.txt");
        String[] args = {};
        //First, verify the output using run()
        Manager manager = new Manager();
        try {
            manager.init(args);
            manager.run();

            String exportFilePath = ManagerTest.EXPORT_FILE_DIR + SLASH + exportFileName;
            File report = new File(exportFilePath);
            report.deleteOnExit();

            assertTrue(report.exists());

            byte[] out = getBytes(report);
            String corbOutput = new String(out).trim();
            String expectedOutput = "This is being returned from the PRE-BATCH-MODULE which is often used for column headers.\n"
                    + "This is a file generated by the XQUERY-MODULE (Transform) which typically contains a report.  This information [Hello from the URIS-FILE!] was passed from the Selector.\n"
                    + POST_XQUERY_MODULE_OUTPUT;
            boolean passed = expectedOutput.equals(corbOutput);
            clearFile(report);

            assertTrue(passed);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        //Then verify the exit code when using the main() method
        exit.expectSystemExitWithStatus(Manager.EXIT_CODE_SUCCESS);
        Manager.main(args);
    }

    @Test
    public void testManagersPreBatchTask() {
        clearSystemProperties();
        String exportFileName = "testManagersPreBatchTask.txt";
        ManagerTest.setDefaultSystemProperties();
        System.setProperty(Options.EXPORT_FILE_NAME, exportFileName);
        String[] args = {};
        //First, verify output executing run()
        Manager manager = new Manager();
        try {
            manager.init(args);
            manager.run();

            String exportFilePath = ManagerTest.EXPORT_FILE_DIR + SLASH + exportFileName;
            File report = new File(exportFilePath);
            report.deleteOnExit();

            assertTrue(report.exists());
            byte[] out = getBytes(report);
            String corbOutput = new String(out).trim();
            String expectedOutput = "This is being returned from the PRE-BATCH-MODULE which is often used for column headers.";
            boolean passed = corbOutput.startsWith(expectedOutput);
            clearFile(report);

            assertTrue(passed);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        //Then, verify the exit code running main()
        exit.expectSystemExitWithStatus(Manager.EXIT_CODE_SUCCESS);
        Manager.main(args);
    }

    @Test
    public void testManagersPostBatchTask() {
        clearSystemProperties();
        String exportFileName = "testManagersPostBatchTask.txt";
        ManagerTest.setDefaultSystemProperties();
        System.setProperty(Options.EXPORT_FILE_NAME, exportFileName);
        String[] args = {};
        //First, verify the output using run()
        Manager manager = new Manager();
        try {
            manager.init(args);
            manager.run();

            String exportFilePath = ManagerTest.EXPORT_FILE_DIR + SLASH + exportFileName;
            File report = new File(exportFilePath);
            assertTrue(report.exists());
            byte[] out = getBytes(report);
            String corbOutput = new String(out).trim();
            boolean passed = corbOutput.endsWith(POST_XQUERY_MODULE_OUTPUT);

            clearFile(report);
            assertTrue(passed);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        //Then verify the exit code when using the main() method
        exit.expectSystemExitWithStatus(Manager.EXIT_CODE_SUCCESS);
        Manager.main(args);
    }

    @Test
    public void testManagersPostBatchTaskZip() {
        clearSystemProperties();
        String exportFileName = "testManagersPostBatchTaskZip.txt";
        ManagerTest.setDefaultSystemProperties();
        System.setProperty(Options.EXPORT_FILE_NAME, exportFileName);
        System.setProperty(Options.EXPORT_FILE_AS_ZIP, Boolean.toString(true));
        String[] args = {};
        //First, verify the output using run()
        Manager manager = new Manager();
        try {
            manager.init(args);
            manager.run();

            String zippedExportFilePath = ManagerTest.EXPORT_FILE_DIR + SLASH + exportFileName + ".zip";
            File report = new File(zippedExportFilePath);

            clearFile(report);
            assertTrue(report.exists());
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        //Then verify the exit code when using the main() method
        exit.expectSystemExitWithStatus(Manager.EXIT_CODE_SUCCESS);
        Manager.main(args);
    }

    @Test
    public void testManagerJavaScriptTransform() {
        clearSystemProperties();
        String exportFileName = "testManagerJavaScriptTransform.txt";
        ManagerTest.setDefaultSystemProperties();
        System.setProperty(Options.PROCESS_MODULE, "src/test/resources/mod-print-uri.sjs|ADHOC");
        System.setProperty("XQUERY-MODULE.foo", "bar1");
        System.setProperty(Options.EXPORT_FILE_NAME, exportFileName);
        String[] args = {};
        //First, verify the output using run()
        Manager manager = new Manager();
        try {
            manager.init(args);
            manager.run();

            String exportFilePath = ManagerTest.EXPORT_FILE_DIR + SLASH + exportFileName;
            File report = new File(exportFilePath);
            report.deleteOnExit();

            assertTrue(report.exists());
            byte[] out = getBytes(report);
            String corbOutput = new String(out).trim();
            String expectedOutput = "object-id-1=bar1";
            boolean passed = corbOutput.contains(expectedOutput);
            clearFile(report);
            assertTrue(passed);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        //Then verify the exit code when using the main() method
        exit.expectSystemExitWithStatus(Manager.EXIT_CODE_SUCCESS);
        Manager.main(args);
    }

    @Test
    public void testMainNullArgs() {
        String[] args = null;
        exit.expectSystemExit();
        Manager.main(args);
        List<LogRecord> records = testLogger.getLogRecords();
        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertEquals(CORB_INIT_ERROR_MSG, records.get(0).getMessage());
    }

    @Test
    public void testMainException() {
        String[] args = ManagerTest.getDefaultArgs();
        exit.expectSystemExit();
        Manager.main(args);
        List<LogRecord> records = testLogger.getLogRecords();
        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertEquals(CORB_INIT_ERROR_MSG, records.get(0).getMessage());
    }

    @Test
    public void testCommandFilePause() {
        clearSystemProperties();
        File exportFile = new File(ManagerTest.EXPORT_FILE_DIR, ManagerTest.EXPORT_FILE_NAME);
        exportFile.deleteOnExit();
        File commandFile = new File(ManagerTest.EXPORT_FILE_DIR, Math.random() + EXT_TXT);
        commandFile.deleteOnExit();
        System.setProperty(Options.XCC_CONNECTION_URI, ManagerTest.XCC_CONNECTION_URI);
        System.setProperty(Options.URIS_FILE, ManagerTest.URIS_FILE);
        System.setProperty(Options.THREAD_COUNT, Integer.toString(1));
        System.setProperty(Options.PROCESS_MODULE, TRANSFORM_SLOW_MODULE);
        System.setProperty(Options.PROCESS_TASK, ManagerTest.PROCESS_TASK);
        System.setProperty(Options.EXPORT_FILE_NAME, ManagerTest.EXPORT_FILE_NAME);
        System.setProperty(Options.EXPORT_FILE_DIR, ManagerTest.EXPORT_FILE_DIR);
        System.setProperty(Options.COMMAND_FILE, commandFile.getAbsolutePath());

        Runnable pause = () -> {
            Properties props = new Properties();
            props.put(Options.COMMAND, SLOW_CMD);
            File commandFile1 = new File(System.getProperty(Options.COMMAND_FILE));
            try {
                if (commandFile1.createNewFile()) {
                    try (final FileOutputStream fos = new FileOutputStream(commandFile1)) {
                        props.store(fos, null);
                    }
                }
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        };
        Runnable resume = () -> {
            Properties props = new Properties();
            props.put(Options.COMMAND, "RESUME");
            props.put(Options.THREAD_COUNT, Integer.toString(6));
            File commandFile1 = new File(System.getProperty(Options.COMMAND_FILE));
            try (final FileOutputStream fos = new FileOutputStream(commandFile1)) {
                props.store(fos, null);
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        };
        ScheduledExecutorService service = Executors.newScheduledThreadPool(1);
        service.schedule(pause, 1, TimeUnit.SECONDS);
        service.schedule(resume, 5, TimeUnit.SECONDS);

        Manager instance = new Manager();
        try {
            instance.init(new String[0]);
            instance.run();
            int lineCount = FileUtils.getLineCount(exportFile);
            assertEquals(8, lineCount);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        List<LogRecord> records = testLogger.getLogRecords();
        assertTrue(containsLogRecord(records, PAUSING));
        assertTrue(containsLogRecord(records, RESUMING));
    }

    @Test
    public void testCommandFilePauseResumeWhenCommandFileChangedAndNoCommand() {
        clearSystemProperties();
        File exportFile = new File(ManagerTest.EXPORT_FILE_DIR, ManagerTest.EXPORT_FILE_NAME);
        exportFile.deleteOnExit();
        File commandFile = new File(ManagerTest.EXPORT_FILE_DIR, Math.random() + EXT_TXT);
        commandFile.deleteOnExit();
        System.setProperty(Options.XCC_CONNECTION_URI, ManagerTest.XCC_CONNECTION_URI);
        System.setProperty(Options.URIS_FILE, ManagerTest.URIS_FILE);
        System.setProperty(Options.THREAD_COUNT, Integer.toString(1));
        System.setProperty(Options.PROCESS_MODULE, TRANSFORM_SLOW_MODULE);
        System.setProperty(Options.PROCESS_TASK, ManagerTest.PROCESS_TASK);
        System.setProperty(Options.EXPORT_FILE_NAME, ManagerTest.EXPORT_FILE_NAME);
        System.setProperty(Options.EXPORT_FILE_DIR, ManagerTest.EXPORT_FILE_DIR);
        System.setProperty(Options.COMMAND_FILE, commandFile.getAbsolutePath());
        Runnable pause = () -> {
            Properties props = new Properties();
            props.put(Options.COMMAND, SLOW_CMD);
            File commandFile1 = new File(System.getProperty(Options.COMMAND_FILE));
            try {
                if (commandFile1.createNewFile()) {
                    try (final FileOutputStream fos = new FileOutputStream(commandFile1)) {
                        props.store(fos, null);
                    }
                }
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        };
        Runnable resume = () -> {
            Properties props = new Properties();
            File commandFile1 = new File(System.getProperty(Options.COMMAND_FILE));
            try (final FileOutputStream fos = new FileOutputStream(commandFile1)) {
                props.store(fos, null);
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        };
        ScheduledExecutorService service = Executors.newScheduledThreadPool(1);
        service.schedule(pause, 1, TimeUnit.SECONDS);
        service.schedule(resume, 4, TimeUnit.SECONDS);

        Manager instance = new Manager();
        try {
            instance.init(new String[0]);
            instance.run();
            int lineCount = FileUtils.getLineCount(exportFile);
            assertEquals(8, lineCount);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        List<LogRecord> records = testLogger.getLogRecords();
        assertTrue(containsLogRecord(records, PAUSING));
        assertTrue(containsLogRecord(records, RESUMING));
    }

    @Test
    public void testCommandFileStop() {
        clearSystemProperties();
        File exportFile = new File(ManagerTest.EXPORT_FILE_DIR, ManagerTest.EXPORT_FILE_NAME);
        exportFile.deleteOnExit();
        File commandFile = new File(ManagerTest.EXPORT_FILE_DIR, Math.random() + EXT_TXT);
        commandFile.deleteOnExit();
        System.setProperty(Options.XCC_CONNECTION_URI, ManagerTest.XCC_CONNECTION_URI);
        System.setProperty(Options.URIS_FILE, ManagerTest.URIS_FILE);
        System.setProperty(Options.THREAD_COUNT, Integer.toString(1));
        System.setProperty(Options.PROCESS_MODULE, TRANSFORM_SLOW_MODULE);
        System.setProperty(Options.PROCESS_TASK, ManagerTest.PROCESS_TASK);
        System.setProperty(Options.EXPORT_FILE_NAME, ManagerTest.EXPORT_FILE_NAME);
        System.setProperty(Options.EXPORT_FILE_DIR, ManagerTest.EXPORT_FILE_DIR);
        System.setProperty(Options.COMMAND_FILE, commandFile.getAbsolutePath());
        Runnable stop = () -> {
            Properties props = new Properties();
            props.put(Options.COMMAND, "STOP");
            File commandFile1 = new File(System.getProperty(Options.COMMAND_FILE));
            try {
                if (commandFile1.createNewFile()) {
                    try (final FileOutputStream fos = new FileOutputStream(commandFile1)) {
                        props.store(fos, null);
                    }
                }
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        };
        ScheduledExecutorService service = Executors.newScheduledThreadPool(1);
        service.schedule(stop, 1, TimeUnit.SECONDS);
        exit.expectSystemExitWithStatus(Manager.EXIT_CODE_STOP_COMMAND);
        Manager.main(new String[0]);
        try {
            int lineCount = FileUtils.getLineCount(exportFile);
            assertNotEquals(8, lineCount);
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        List<LogRecord> records = testLogger.getLogRecords();
        assertTrue(containsLogRecord(records, new LogRecord(Level.INFO, "cleaning up")));
    }

    @Test
    public void testCommandFileLowerThreads() {
        clearSystemProperties();
        File exportFile = new File(ManagerTest.EXPORT_FILE_DIR, ManagerTest.EXPORT_FILE_NAME);
        exportFile.deleteOnExit();
        File commandFile = new File(ManagerTest.EXPORT_FILE_DIR, Math.random() + EXT_TXT);
        commandFile.deleteOnExit();
        System.setProperty(Options.XCC_CONNECTION_URI, ManagerTest.XCC_CONNECTION_URI);
        System.setProperty(Options.URIS_FILE, ManagerTest.URIS_FILE);
        System.setProperty(Options.THREAD_COUNT, Integer.toString(3));
        System.setProperty(Options.PROCESS_MODULE, TRANSFORM_SLOW_MODULE);
        System.setProperty(Options.PROCESS_TASK, ManagerTest.PROCESS_TASK);
        System.setProperty(Options.EXPORT_FILE_NAME, ManagerTest.EXPORT_FILE_NAME);
        System.setProperty(Options.EXPORT_FILE_DIR, ManagerTest.EXPORT_FILE_DIR);
        System.setProperty(Options.COMMAND_FILE, commandFile.getAbsolutePath());

        Runnable adjustThreads = () -> {
            File commandFile1 = new File(System.getProperty(Options.COMMAND_FILE));
            try {
                if (commandFile1.createNewFile()) {
                    Properties props = new Properties();
                    props.put(Options.THREAD_COUNT, Integer.toString(1));
                    try (final FileOutputStream fos = new FileOutputStream(commandFile1)) {
                        props.store(fos, null);
                    }
                }
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        };

        Manager instance = new Manager();
        try {
            instance.init(new String[0]);

            ScheduledExecutorService service = Executors.newScheduledThreadPool(1);
            service.schedule(adjustThreads, 1, TimeUnit.SECONDS);
            instance.run();
            int lineCount = FileUtils.getLineCount(exportFile);
            assertEquals(8, lineCount);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            fail();
        }
        List<LogRecord> records = testLogger.getLogRecords();
        assertTrue(containsLogRecord(records, new LogRecord(Level.INFO, "Changed {0} to {1}")));
    }

}
