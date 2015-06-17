package org.sonatype.flexmojos.coverage.util

import net.sourceforge.cobertura.util.FileFinder
import net.sourceforge.cobertura.util.Source

/**
 * Created by bor on 17.6.2015.
 */
class FileFinderImpl extends FileFinder {
    public Source getSource( String fileName )
    {
        Source source = super.getSource( fileName.replace( ".java", ".as" ) );

        if ( source == null )
        {
            source = super.getSource( fileName.replace( ".java", ".mxml" ) );
        }
        return source;
    }
}
