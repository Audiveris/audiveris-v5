//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                             M e d i a n G r a y F i l t e r T e s t                            //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2021. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.image;

import static org.junit.Assert.fail;

import org.junit.Ignore;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Class {@code MedianGrayFilterTest}
 *
 * @author Hervé Bitteur
 */
public class MedianGrayFilterTest
{

    final int width = 10;

    final int height = 4;

    /**
     * Creates a new MedianGrayFilterTest object.
     */
    public MedianGrayFilterTest ()
    {
    }

    //    @Test
    public void testProcess_black_1 ()
    {
        System.out.println("\nprocess_black_1");

        BufferedImage image = createBlackImage();
        dump(image, "initial black:");

        MedianGrayFilter instance = new MedianGrayFilter(1);
        BufferedImage expResult = image;
        BufferedImage result = instance.filter(image);
        dump(result, "result:");

        if (!areEqual(expResult, result)) {
            fail("Images are not equal");
        }
    }

    //    @Test
    public void testProcess_diag_1 ()
    {
        System.out.println("\nprocess_diag_1");

        BufferedImage image = createDiagImage();
        dump(image, "initial diag:");

        MedianGrayFilter instance = new MedianGrayFilter(1);
        BufferedImage expResult = image;
        BufferedImage result = instance.filter(image);
        dump(result, "result:");

        if (!areEqual(expResult, result)) {
            fail("Images are not equal");
        }
    }

    @Test
    public void testProcess_ex_1 ()
    {
        System.out.println("\nprocess_ex_1");

        final int[] pixel = new int[1];
        pixel[0] = 0;

        BufferedImage image = createWhiteImage(8, 8);
        final WritableRaster out = image.getRaster();

        out.setPixel(2, 5, pixel);
        out.setPixel(3, 2, pixel);
        out.setPixel(3, 3, pixel);
        out.setPixel(3, 4, pixel);
        out.setPixel(4, 2, pixel);
        out.setPixel(4, 3, pixel);

        dump(image, "initial ex:");

        MedianGrayFilter instance = new MedianGrayFilter(1);
        BufferedImage expResult = createWhiteImage(8, 8);
        final WritableRaster exp = expResult.getRaster();
        exp.setPixel(3, 3, pixel);
        exp.setPixel(4, 3, pixel);
        dump(expResult, "Expected:");

        BufferedImage result = instance.filter(image);
        dump(result, "result:");

        if (!areEqual(expResult, result)) {
            fail("Images are not equal");
        }
    }

    @Ignore
    @Test
    public void testProcess_white_1 ()
    {
        System.out.println("\nprocess_white_1");

        BufferedImage image = createWhiteImage(width, height);
        dump(image, "initial white:");

        MedianGrayFilter instance = new MedianGrayFilter(1);
        BufferedImage result = instance.filter(image);
        dump(result, "result:");

        if (!areEqual(image, result)) {
            fail("Images are not equal");
        }
    }

    private boolean areEqual (BufferedImage one,
                              BufferedImage two)
    {
        final Raster oneRaster = one.getRaster();
        final Raster twoRaster = two.getRaster();
        final int width = one.getWidth();
        final int height = one.getHeight();
        final int[] onePixel = new int[1];
        final int[] twoPixel = new int[1];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                oneRaster.getPixel(x, y, onePixel);
                twoRaster.getPixel(x, y, twoPixel);

                if (onePixel[0] != twoPixel[0]) {
                    return false;
                }
            }
        }

        return true;
    }

    private BufferedImage createBlackImage ()
    {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        final WritableRaster out = img.getRaster();
        final int[] pixel = new int[1];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixel[0] = 0;
                out.setPixel(x, y, pixel);
            }
        }

        return img;
    }

    private BufferedImage createDiagImage ()
    {
        final BufferedImage img = createWhiteImage(width, height);

        final WritableRaster out = img.getRaster();
        final int[] pixel = new int[1];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x == y) {
                    pixel[0] = 0;
                    out.setPixel(x, y, pixel);
                }
            }
        }

        return img;
    }

    private BufferedImage createWhiteImage (int width,
                                            int height)
    {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        final WritableRaster out = img.getRaster();
        final int[] pixel = new int[1];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixel[0] = 255;
                out.setPixel(x, y, pixel);
            }
        }

        return img;
    }

    private void dump (BufferedImage img,
                       String title)
    {
        final WritableRaster raster = img.getRaster();
        final int width = img.getWidth();
        final int height = img.getHeight();
        final int[] pixel = new int[1];

        System.out.println(title);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.getPixel(x, y, pixel);
                System.out.printf("%4d", pixel[0]);
            }

            System.out.println();
        }
    }
}
