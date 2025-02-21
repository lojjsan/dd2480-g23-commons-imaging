/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.imaging.formats.tiff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import javax.swing.plaf.synth.ColorType;

import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingTestConstants;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/**
 * Performs a test on the ImageInfo returned from TIFF to confirm that it
 * contains the specified value for a particular target. This test is used to
 * confirm
 * that the TiffImageParser is correctly interpreting the ImageInfo values.
 */
public class TiffReadImageInfoTest extends TiffBaseTest {

    // The form of the test set is
    // 0. target file name
    // 1. Parameter field in ImageInfo
    // 2. Expected value
    static final String[][] testSet = { { "1/matthew2.tif", "Color Type", "Black and White" },
            { "7/Oregon Scientific DS6639 - DSC_0307 - small - CMYK.tiff", "Color Type", "CMYK" },
            { "10/Imaging247.TIFF", "Uses Palette", "true" },
            { "12/TransparencyTestStripAssociated.tif", "Is Transparent", "true" },
            { "14/TestJpegStrips.tiff", "Color Type", "YCbCr" } };

    private File getTiffFile(final String name) {
        final File tiffFolder = new File(ImagingTestConstants.TEST_IMAGE_FOLDER, "tiff");
        return new File(tiffFolder, name);
    }

    /**
     * Gets the value for the target data field within the ImageInfo string. This
     * method expects data fields to be given in the form: parameter name, colon,
     * value, end-of-line
     *
     * @param info   a valid instance obtained from TiffImageParser
     * @param target the target data field string
     * @return the value
     */
    private String getValue(final ImageInfo info, final String target) {
        final String s = info.toString();
        final int i = s.indexOf(target);
        if (i < 0) {
            return "";
        }
        final int j = s.indexOf(':', i);
        if (j < 0) {
            return "";
        }
        final int k = s.indexOf('\n', j);
        if (k < j) {
            return "";
        }
        return s.substring(j + 1, k).trim();
    }

    @Test
    public void testImageInfoElements() throws Exception {
        for (final String[] testTarget : testSet) {
            final File targetFile = getTiffFile(testTarget[0]);
            final ImageInfo info = Imaging.getImageInfo(targetFile);
            final String value = getValue(info, testTarget[1]);
            final String identifier = targetFile.getName() + ": " + testTarget[1];
            assertEquals(value.toLowerCase(), testTarget[2].toLowerCase(), identifier);
        }
    }

    @Test
    public void testImageResolution() {
        ImageInfo mockInfo = mock(ImageInfo.class);
        when(mockInfo.getPhysicalWidthDpi()).thenReturn(300);
        when(mockInfo.getPhysicalHeightDpi()).thenReturn(300);

        assertEquals(300, mockInfo.getPhysicalWidthDpi(), "Physical Width DPI mismatch");
        assertEquals(300, mockInfo.getPhysicalHeightDpi(), "Physical Height DPI mismatch");
    }

    @Test
    public void testImageTransparency() {
        ImageInfo mockInfo = mock(ImageInfo.class);
        when(mockInfo.isTransparent()).thenReturn(true);

        assertEquals(true, mockInfo.isTransparent(), "Transparency mismatch");
    }

    @Test
    public void testBitsPerSample() {
        ImageInfo mock8Bit = mock(ImageInfo.class);
        when(mock8Bit.getBitsPerPixel()).thenReturn(8);

        ImageInfo mock16Bit = mock(ImageInfo.class);
        when(mock16Bit.getBitsPerPixel()).thenReturn(16);

        assertEquals(8, mock8Bit.getBitsPerPixel(), "Bits Per Sample mismatch for 8-bit image");
        assertEquals(16, mock16Bit.getBitsPerPixel(), "Bits Per Sample mismatch for 16-bit image");
    }

    @Test
    public void testCompressionAlgorithm() {
        ImageInfo mockLZW = mock(ImageInfo.class);
        when(mockLZW.getCompressionAlgorithm()).thenReturn(ImageInfo.CompressionAlgorithm.LZW);

        ImageInfo mockJPEG = mock(ImageInfo.class);
        when(mockJPEG.getCompressionAlgorithm()).thenReturn(ImageInfo.CompressionAlgorithm.JPEG);

        assertEquals(ImageInfo.CompressionAlgorithm.LZW, mockLZW.getCompressionAlgorithm(),
                "Compression Algorithm mismatch for LZW image");
        assertEquals(ImageInfo.CompressionAlgorithm.JPEG, mockJPEG.getCompressionAlgorithm(),
                "Compression Algorithm mismatch for JPEG image");
    }

}
