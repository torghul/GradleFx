/*
 * Copyright (c) 2011 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradlefx.tasks

import groovy.text.SimpleTemplateEngine
import org.apache.tools.ant.BuildException
import org.flexunit.ant.FlexUnitSocketServer
import org.flexunit.ant.FlexUnitSocketThread
import org.flexunit.ant.launcher.OperatingSystem
import org.flexunit.ant.launcher.commands.player.PlayerCommand
import org.flexunit.ant.launcher.commands.player.PlayerCommandFactory
import org.flexunit.ant.launcher.contexts.ExecutionContext
import org.flexunit.ant.launcher.contexts.ExecutionContextFactory
import org.flexunit.ant.report.Report
import org.flexunit.ant.report.Reports
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileTree
import org.gradle.api.file.FileTreeElement
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.TaskAction
import org.gradle.mvn3.org.codehaus.plexus.util.xml.Xpp3Dom
import org.gradle.mvn3.org.codehaus.plexus.util.xml.Xpp3DomBuilder
import org.gradlefx.cli.compiler.AntBasedCompilerProcess
import org.gradlefx.cli.compiler.CompilerJar
import org.gradlefx.cli.compiler.CompilerProcess
import org.gradlefx.cli.compiler.DefaultCompilerResultHandler
import org.gradlefx.cli.instructions.CompilerInstructionsBuilder
import org.gradlefx.cli.instructions.flexsdk.FlexUnitAppInstructions as FlexSDKFlexUnitAppInstructions
import org.gradlefx.cli.instructions.airsdk.standalone.actionscriptonly.FlexUnitAppInstructions as NoFlexSDKFlexUnitAppInstructions
import org.gradlefx.configuration.FlexUnitAntTasksConfigurator
import org.gradlefx.conventions.FlexUnitConvention
import org.gradlefx.conventions.GradleFxConvention
import org.gradlefx.util.PathToClassNameExtractor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sonatype.flexmojos.coverage.CoverageReportRequest
import org.sonatype.flexmojos.coverage.cobertura.CoberturaCoverageReport
import org.sonatype.flexmojos.test.report.TestCoverageReport

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future;

/*
 * A Gradle task to execute FlexUnit tests.
 */
class TestCoverage extends DefaultTask {

    private static final Logger LOG = LoggerFactory.getLogger Test

    GradleFxConvention flexConvention
    PathToClassNameExtractor pathToClassNameExtractor

    public TestCoverage() {
        group = TaskGroups.VERIFICATION
        description = "Run the FlexUnit tests with coverage."

        logging.setLevel LogLevel.INFO

        flexConvention = project.convention.plugins.flex

        dependsOn Tasks.COPY_TEST_RESOURCES_TASK_NAME

        pathToClassNameExtractor = new PathToClassNameExtractor()
        reports = new Reports()
    }

    @TaskAction
    def runFlexUnit() {
        if(hasTests()) {
            createFlexUnitRunnerFromTemplate()

            compileTestRunner()

            configureAntWithFlexUnit()
            runTests()
        } else {
            LOG.info "Skipping tests since no tests exist"
        }
    }

    private void compileTestRunner() {
        def compilerInstructions = createCompilerInstructionsBuilder().buildInstructions()
        def compilerJar = flexConvention.hasFlexSDK() ? CompilerJar.mxmlc : CompilerJar.mxmlc_cli

        CompilerProcess compilerProcess = new AntBasedCompilerProcess(ant, compilerJar, new File(flexConvention.flexHome))
        compilerProcess.with {
            jvmArguments = flexConvention.jvmArguments
            compilerOptions = compilerInstructions
            compilerResultHandler = new DefaultCompilerResultHandler()
        }
        compilerProcess.compile()
    }

    /**
     * Determines which compiler instructions will be used for this project.
     */
    private CompilerInstructionsBuilder createCompilerInstructionsBuilder() {
        if(flexConvention.hasFlexSDK()) {
            new FlexSDKFlexUnitAppInstructions(project)
        } else {
            new NoFlexSDKFlexUnitAppInstructions(project)
        }
    }

    private boolean hasTests() {
        String nonEmptyTestDir = flexConvention.testDirs.find { String testDir ->
            if(project.file(testDir).exists()) {
                FileTree fileTree = project.fileTree(testDir)
                fileTree.includes = flexConvention.flexUnit.includes
                fileTree.excludes = flexConvention.flexUnit.excludes

                return !fileTree.empty
            } else {
                return false
            }
        }

        return nonEmptyTestDir != null
    }

    private void createFlexUnitRunnerFromTemplate() {
        String templateText = retreiveTemplateText()
        String templateFileName = getTemplateFileName()

        Set<String> fullyQualifiedNames = gatherFullyQualifiedTestClassNames()
        Set<String> classNames = gatherTestClassNames()
        def binding = [
                "fullyQualifiedNames": fullyQualifiedNames,
                "testClasses": classNames
        ]
        def engine = new SimpleTemplateEngine()
        def template = engine.createTemplate(templateText).make(binding)

        // you can't write to a directory that doesn't exist
        def outputDir = project.file(flexConvention.flexUnit.toDir)
        if (!outputDir.exists()) outputDir.mkdirs()

        File destinationFile = project.file("${flexConvention.flexUnit.toDir}/${templateFileName}")
        destinationFile.createNewFile()
        destinationFile.write(template.toString())
    }

    private String retreiveTemplateText() {
        def templateText
        if(flexConvention.flexUnit.template == null) {
            //use the standard template
            String templatePath = "/templates/flexunit/FlexUnitRunner.mxml"
            templateText = getClass().getResourceAsStream(templatePath).text
        } else {
            templateText = project.file(flexConvention.flexUnit.template).text
        }

        templateText
    }

    private String getTemplateFileName() {
        def name
        if(flexConvention.flexUnit.template != null && flexConvention.flexUnit.template.endsWith(".as")) {
            name = "FlexUnitRunner.as"
        } else {
            name = "FlexUnitRunner.mxml"
        }

        name
    }

    def Set<String> gatherFullyQualifiedTestClassNames() {
        List<String> paths = []
        flexConvention.testDirs.each { String testDir ->
            FileTree fileTree = project.fileTree(testDir)
            fileTree.includes = flexConvention.flexUnit.includes
            fileTree.excludes = flexConvention.flexUnit.excludes

            fileTree.visit { FileTreeElement includedFile ->
                if(!includedFile.isDirectory()) {
                    def fullyQualifiedClassname =
                            pathToClassNameExtractor.convertPathStringToFullyQualifiedClassName(includedFile.relativePath.pathString)
                    paths.add(fullyQualifiedClassname)
                }
            }
        }

        return paths
    }

    def Set<String> gatherTestClassNames() {
        //fully qualified test class names are required because test 
        //classes can have the same name but in different package structures.
        gatherFullyQualifiedTestClassNames()
    }

    private void runTests() {
        FlexUnitConvention flexUnit = flexConvention.flexUnit
        File reportDir = project.file flexUnit.toDir

        // you can't write to a directory that doesn't exist
        if (!reportDir.exists()) reportDir.mkdirs()

        if (flexUnit.command == null) {
            throw new Exception(
                    "The Flash player executable is not found. Either set the FLASH_PLAYER_EXE " +
                            "environment variable or set the 'flexUnit.command' property."
            )
        }

        File[] sources = initCoverageInstrumentation()

        try {
            logger.info("Starting tests...")
            Future<Object> daemon = setupSocketThread()

            PlayerCommand player = obtainPlayer()

            ExecutionContext context = obtainContext(player)

            //start the execution context
            context.start()

            Process process = player.launch()

            // block until daemon is completely done with all test data
            daemon.get()

            //stop the execution context now that socket thread is done
            context.stop(process)

            logger.info("Tests complete.")
            // print summaries and check for failure
            analyzeReports();
        } catch (Exception e) {
            throw new BuildException(e);
        }

        generateCoverageReports(sources)

        if (ant.properties[flexUnit.failureProperty] == "true") {
            def msg = 'Tests failed'
            if (flexUnit.ignoreFailures) { LOG.warn(msg) }
            else { throw new Exception(msg) }
        }
    }

    private void generateCoverageReports(File[] sources) {
        FlexUnitConvention flexUnit = flexConvention.flexUnit

        if (createCoverageReports == true) {
            try
            {
                logger.info("Analyzing coverage data...");
                for (Report report : reports.values())
                {
                    for (String reportxml : report.xmls)
                    {
                        for (Xpp3Dom child : Xpp3DomBuilder.build(new StringReader(reportxml)).getChildren("coverage"))
                        {
                            TestCoverageReport tcr = new TestCoverageReport(child)
                            reporter.addResult(tcr.getClassname(), tcr.getTouchs())
                        }
                    }
                }

                logger.info("Generating coverage report...");
                List<String> formats = new ArrayList<String>()
                formats.add("html")
                formats.add("xml")
                CoverageReportRequest request = new CoverageReportRequest(project.file(flexUnit.toDir), formats, "UTF-8",
                        project.file(flexUnit.toDir), sources)
                reporter.generateReport(request)
            }
            catch (Exception e)
            {
                throw new BuildException(e)
            }
        }
    }
    private boolean createCoverageReports;
    /**
     * End of test report run. Called at the end of a test run. If verbose is set
     * to true reads all suites in the suite list and prints out a descriptive
     * message including the name of the suite, number of tests run and number of
     * tests failed, ignores any errors. If any tests failed during the test run,
     * the build is halted.
     */
    protected void analyzeReports()
    {
//        LoggingUtil.log("Analyzing reports ...");

        // print out all report summaries
//        LoggingUtil.log("\n" + reports.getSummary(), true);
        logger.info("Analyzing reports...")
        FlexUnitConvention flexUnit = flexConvention.flexUnit

        if (reports.hasFailures())
        {
            flexUnit.failureProperty("true")

            if (flexUnit.haltOnFailure)
            {
                throw new BuildException("FlexUnit tests failed during the test run.");
            }
        }
    }

    private CoberturaCoverageReport reporter = null;

    private File[] initCoverageInstrumentation() {
        FlexUnitConvention flexUnit = flexConvention.flexUnit

        File[] sources
        createCoverageReports = true
        try
        {
            sources = new File[flexUnit.coverageSources.size()];
            for (int j=0;j<flexUnit.coverageSources.size();j++)
            {
                sources[j] = new File(flexUnit.coverageSources.get(j));
            }
            if (sources.length > 0) {
                logger.info("Instrumenting ${flexConvention.flexUnit.toDir}/${flexConvention.flexUnit.swfName}...")

                reporter = new CoberturaCoverageReport()
                reporter.initialize()
                String[] excludes = new String[flexUnit.coverageExclusions.size()]
                for (int i=0;i<flexUnit.coverageExclusions.size();i++)
                {
                    excludes[i] = flexUnit.coverageExclusions.get(i)
                }
                reporter.setExcludes(excludes)
                def swf = project.file("${flexConvention.flexUnit.toDir}/${flexConvention.flexUnit.swfName}")
                reporter.instrument(swf, sources)
                logger.info("Instrumenting complete.")
            } else {
                createCoverageReports = false
            }
            return sources
        }
        catch (Exception e)
        {
            throw new BuildException(e)
        }
    }

    private PlayerCommand obtainPlayer() {
        FlexUnitConvention flexUnit = flexConvention.flexUnit
        PlayerCommand player = PlayerCommandFactory.createPlayer(OperatingSystem.identify(), flexUnit.player, new File(flexUnit.command), flexUnit.localTrusted)
        player.setProject(ant.project)
        player.setSwf(project.file("${flexConvention.flexUnit.toDir}/${flexConvention.flexUnit.swfName}"))

        return player
    }

    /**
     *
     * @param player PlayerCommand which should be executed
     * @return Context to wrap the execution of the PlayerCommand
     */
    protected ExecutionContext obtainContext(PlayerCommand player)
    {
        FlexUnitConvention flexUnit = flexConvention.flexUnit
        ExecutionContext context = ExecutionContextFactory.createContext(
                OperatingSystem.identify(),
                flexUnit.headless,
                flexUnit.display)

        context.setProject(ant.project)
        context.setCommand(player)

        return context
    }

    /**
     * Create a server socket for receiving the test reports from FlexUnit. We
     * read and write the test reports inside of a Thread.
     */
    protected Future<Object> setupSocketThread()
    {
        FlexUnitConvention flexUnit = flexConvention.flexUnit
        def usePolicyfile = !flexUnit.localTrusted && flexUnit.player.equals("flash")
        def socketTimeout = 60000
        def serverBufferSize = 262144

        // Create server for use by thread
        FlexUnitSocketServer server = new FlexUnitSocketServer(flexUnit.port,
                socketTimeout, serverBufferSize,
                usePolicyfile)

        // Get handle to specialized object to run in separate thread.
        Callable<Object> operation = new FlexUnitSocketThread(server,
                project.file(flexUnit.toDir), reports)

        // Get handle to service to run object in thread.
        ExecutorService executor = Executors.newSingleThreadExecutor()

        // Run object in thread and return Future.
        return executor.submit(operation)
    }

    private Reports reports;

    private void configureAntWithFlexUnit() {
        new FlexUnitAntTasksConfigurator(project).configure()
    }

}
