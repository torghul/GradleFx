package org.sonatype.flexmojos.coverage.cobertura;

import net.sourceforge.cobertura.coveragedata.ClassData;
import net.sourceforge.cobertura.coveragedata.CoverageDataFileHandler;
import net.sourceforge.cobertura.coveragedata.ProjectData;
import net.sourceforge.cobertura.reporting.ComplexityCalculator;
import net.sourceforge.cobertura.reporting.html.HTMLReport;
import net.sourceforge.cobertura.reporting.xml.SummaryXMLReport;
import net.sourceforge.cobertura.reporting.xml.XMLReport;
import net.sourceforge.cobertura.util.FileFinder;

import org.sonatype.flexmojos.coverage.AbstractCoverageReporter;
import org.sonatype.flexmojos.coverage.CoverageReportException;
import org.sonatype.flexmojos.coverage.CoverageReportRequest;
import org.sonatype.flexmojos.coverage.CoverageReporter;
import org.sonatype.flexmojos.coverage.util.ApparatUtil
import org.sonatype.flexmojos.coverage.util.FileFinderImpl;
import org.sonatype.flexmojos.util.PathUtil;

import apparat.tools.coverage.CoverageObserver;

//@Component( role = CoverageReporter.class, hint = "cobertura", instantiationStrategy = "per-lookup" )
public class CoberturaCoverageReport
    extends AbstractCoverageReporter
    implements CoverageReporter//, Initializable
{

    private ProjectData coverageProjectData;

    public void initialize()
        throws Exception //InitializationException
    {
        this.coverageProjectData = new ProjectData();
    }

    @Override
    protected CoverageObserver getInstrumentationObserver()
    {
        return [
         instrument :
            { file, line ->
                if ( isExcluded( file ) ) {
                    //getLogger().debug("ignoring " + file);
                } else {
                    ClassData classData = coverageProjectData.getOrCreateClassData( ApparatUtil.toClassname( file ) );
                    classData.setSourceFileName( getSourceFilePath( file ) );
                    classData.addLine( line, null, null );
                    classData.setContainsInstrumentationInfo()
                }
            }
        ] as CoverageObserver
    }

    private String getSourceFilePath( String apparatClassname )
    {
        String cn = apparatClassname;
        cn = cn.substring( cn.lastIndexOf( ';' ) + 1 );
        cn = cn.replace( ';', '/' );
        return cn;
    }

    public void generateReport( CoverageReportRequest request )
        throws CoverageReportException
    {
        File dataDirectory = request.getDataDirectory();

        FileFinder finder = new FileFinderImpl()

        List<File> sp = request.getSourcePaths();
        for ( File dir : sp )
        {
            finder.addSourceDirectory( PathUtil.path( dir ) );
        }

        ComplexityCalculator complexity = new ZeroComplexityCalculator( finder );
        try
        {
            File coverageReportDestinationDir = request.getReportDestinationDir();
            coverageReportDestinationDir.mkdirs();

            List<String> format = request.getFormats();
            if ( format.contains( "html" ) )
            {
                String coverageReportEncoding = request.getReportEncoding();
                //if ( StringUtils.isEmpty( coverageReportEncoding ) )
                //{
                    coverageReportEncoding = "UTF-8";
                //}
                new HTMLReport( coverageProjectData, coverageReportDestinationDir, finder, complexity,
                                coverageReportEncoding );
            }

            if ( format.contains( "xml" ) )
            {
                new XMLReport( coverageProjectData, coverageReportDestinationDir, finder, complexity );
            }

            if ( format.contains( "summaryXml" ) )
            {
                new SummaryXMLReport( coverageProjectData, coverageReportDestinationDir, finder, complexity );
            }
        }
        catch ( Exception e )
        {
            throw new CoverageReportException( "Unable to write coverage report", e );
        }

        CoverageDataFileHandler.saveCoverageData( coverageProjectData, new File( dataDirectory, "cobertura.ser" ) );
    }

    public void addResult( String file, Integer[] touchs )
    {
        //getLogger().debug("addresult " + file);

        if ( isExcluded( file ) ) {
            //getLogger().debug("ignoring " + file + " from touch");
        } else {
            ClassData classData = this.coverageProjectData.getOrCreateClassData( ApparatUtil.toClassname( file ) );
            for ( Integer touch : touchs )
            {
                classData.touch( touch, 1 );
            }
            classData.setContainsInstrumentationInfo()
        }
    }

}
