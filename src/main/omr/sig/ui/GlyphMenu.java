//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        G l y p h M e n u                                       //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Herve Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sig.ui;

import omr.glyph.facets.Glyph;

import omr.sheet.Sheet;

import omr.sig.Inter;

import omr.ui.util.AbstractMouseListener;
import omr.ui.util.UIUtil;
import omr.ui.view.LocationDependentMenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Collection;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

/**
 * Class {@code GlyphMenu} displays a collection of glyphs.
 *
 * @author Hervé Bitteur
 */
public class GlyphMenu
        extends LocationDependentMenu
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(GlyphMenu.class);

    //~ Instance fields ----------------------------------------------------------------------------
    private final GlyphListener glyphListener = new GlyphListener();

    private final Sheet sheet;

    //~ Constructors -------------------------------------------------------------------------------
    //-----------//
    // GlyphMenu //
    //-----------//
    /**
     * Creates a new GlyphMenu object.
     *
     * @param sheet the related sheet
     */
    public GlyphMenu (Sheet sheet)
    {
        super("Pile ...");
        this.sheet = sheet;
    }

    //~ Methods ------------------------------------------------------------------------------------
    @Override
    public void updateUserLocation (Rectangle rect)
    {
        // We rebuild the menu items on each update, since the set of glyphs is brand new.
        removeAll();

        Collection<Glyph> glyphs = sheet.getNest().getSelectedGlyphPile();

        if (!glyphs.isEmpty()) {
            UIUtil.insertTitle(this, "Glyphs:");

            for (Glyph glyph : glyphs) {
                //                final Collection<Inter> inters = glyph.getInterpretations();
                //
                //                if (inters.isEmpty()) {
                // Just a glyph item
                JMenuItem item = new JMenuItem(new GlyphAction(glyph));
                item.addMouseListener(glyphListener);
                add(item);

                //                } else {
                //                    // A whole menu of inters for this glyph
                //                    JMenu interMenu = new InterMenu(sheet, glyph, inters);
                //                    interMenu.addMouseListener(glyphListener);
                //                    add(interMenu);
                //                }
            }
        }

        setVisible(!glyphs.isEmpty());

        super.updateUserLocation(rect);
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //---------------//
    // GlyphListener //
    //---------------//
    /**
     * Publish related glyph when entered by mouse.
     */
    private class GlyphListener
            extends AbstractMouseListener
    {
        //~ Methods --------------------------------------------------------------------------------

        @Override
        public void mouseEntered (MouseEvent e)
        {
            JMenuItem item = (JMenuItem) e.getSource();
            GlyphAction action = (GlyphAction) item.getAction();
            action.publish();
        }
    }
}
