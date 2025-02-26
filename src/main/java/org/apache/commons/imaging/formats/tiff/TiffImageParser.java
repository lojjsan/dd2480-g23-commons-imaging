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

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.imaging.AbstractImageParser;
import org.apache.commons.imaging.FormatCompliance;
import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.ImageFormats;
import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.bytesource.ByteSource;
import org.apache.commons.imaging.common.Allocator;
import org.apache.commons.imaging.common.ImageBuilder;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.common.XmpEmbeddable;
import org.apache.commons.imaging.common.XmpImagingParameters;
import org.apache.commons.imaging.formats.tiff.TiffDirectory.ImageDataElement;
import org.apache.commons.imaging.formats.tiff.constants.TiffConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffEpTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffPlanarConfiguration;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.datareaders.ImageDataReader;
import org.apache.commons.imaging.formats.tiff.photometricinterpreters.PhotometricInterpreter;
import org.apache.commons.imaging.formats.tiff.photometricinterpreters.PhotometricInterpreterBiLevel;
import org.apache.commons.imaging.formats.tiff.photometricinterpreters.PhotometricInterpreterCieLab;
import org.apache.commons.imaging.formats.tiff.photometricinterpreters.PhotometricInterpreterCmyk;
import org.apache.commons.imaging.formats.tiff.photometricinterpreters.PhotometricInterpreterLogLuv;
import org.apache.commons.imaging.formats.tiff.photometricinterpreters.PhotometricInterpreterPalette;
import org.apache.commons.imaging.formats.tiff.photometricinterpreters.PhotometricInterpreterRgb;
import org.apache.commons.imaging.formats.tiff.photometricinterpreters.PhotometricInterpreterYCbCr;
import org.apache.commons.imaging.formats.tiff.write.TiffImageWriterLossy;

import org.apache.commons.imaging.formats.tiff.TiffCoverageLogger;

/**
 * Implements methods for reading and writing TIFF files. Instances of this
 * class are invoked from the general Imaging class. Applications that require
 * the use
 * of TIFF-specific features may instantiate and access this class directly.
 */
public class TiffImageParser extends AbstractImageParser<TiffImagingParameters>
        implements XmpEmbeddable<TiffImagingParameters> {

    private static final String DEFAULT_EXTENSION = ImageFormats.TIFF.getDefaultExtension();
    private static final String[] ACCEPTED_EXTENSIONS = ImageFormats.TIFF.getExtensions();

    private Rectangle checkForSubImage(final TiffImagingParameters params) {
        // the params class enforces a correct specification for the
        // sub-image, but does not have knowledge of the actual
        // dimensions of the image that is being read. This method
        // returns the sub-image specification, if any, and leaves
        // further tests to the calling module.
        if (params != null && params.isSubImageSet()) {
            final int ix0 = params.getSubImageX();
            final int iy0 = params.getSubImageY();
            final int iwidth = params.getSubImageWidth();
            final int iheight = params.getSubImageHeight();
            return new Rectangle(ix0, iy0, iwidth, iheight);
        }
        return null;
    }

    public List<byte[]> collectRawImageData(final ByteSource byteSource, final TiffImagingParameters params)
            throws ImagingException, IOException {
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffContents contents = new TiffReader(params != null && params.isStrict()).readDirectories(byteSource,
                true, formatCompliance);

        final List<byte[]> result = new ArrayList<>();
        for (int i = 0; i < contents.directories.size(); i++) {
            final TiffDirectory directory = contents.directories.get(i);
            final List<ImageDataElement> dataElements = directory.getTiffRawImageDataElements();
            for (final ImageDataElement element : dataElements) {
                final byte[] bytes = byteSource.getByteArray(element.offset, element.length);
                result.add(bytes);
            }
        }
        return result;
    }

    @Override
    public boolean dumpImageFile(final PrintWriter pw, final ByteSource byteSource)
            throws ImagingException, IOException {
        try {
            pw.println("tiff.dumpImageFile");

            {
                final ImageInfo imageData = getImageInfo(byteSource);
                if (imageData == null) {
                    return false;
                }

                imageData.toString(pw, "");
            }

            pw.println("");

            // try
            {
                final FormatCompliance formatCompliance = FormatCompliance.getDefault();
                final TiffImagingParameters params = new TiffImagingParameters();
                final TiffContents contents = new TiffReader(true).readContents(byteSource, params, formatCompliance);

                final List<TiffDirectory> directories = contents.directories;
                if (directories == null) {
                    return false;
                }

                for (int d = 0; d < directories.size(); d++) {
                    final TiffDirectory directory = directories.get(d);

                    // Debug.debug("directory offset", directory.offset);

                    for (final TiffField field : directory) {
                        field.dump(pw, Integer.toString(d));
                    }
                }

                pw.println("");
            }
            // catch (Exception e)
            // {
            // Debug.debug(e);
            // pw.println("");
            // return false;
            // }

            return true;
        } finally {
            pw.println("");
        }
    }

    @Override
    protected String[] getAcceptedExtensions() {
        return ACCEPTED_EXTENSIONS;
    }

    @Override
    protected ImageFormat[] getAcceptedTypes() {
        return new ImageFormat[] { ImageFormats.TIFF, 
        };
    }

    @Override
    public List<BufferedImage> getAllBufferedImages(final ByteSource byteSource) throws ImagingException, IOException {
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffReader tiffReader = new TiffReader(true);
        final TiffContents contents = tiffReader.readDirectories(byteSource, true, formatCompliance);
        final List<BufferedImage> results = new ArrayList<>();
        for (int i = 0; i < contents.directories.size(); i++) {
            final TiffDirectory directory = contents.directories.get(i);
            final BufferedImage result = directory.getTiffImage(tiffReader.getByteOrder(), null);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * <p>
     * Gets a buffered image specified by the byte source. The TiffImageParser class
     * features support for a number of options that are unique to the TIFF
     * format. These options can be specified by supplying the appropriate
     * parameters using the keys from the TiffConstants class and the params
     * argument for
     * this method.
     * </p>
     *
     * <p>
     * <strong>Loading Partial Images</strong>
     * </p>
     *
     * <p>
     * The TIFF parser includes support for loading partial images without
     * committing significantly more memory resources than are necessary to store
     * the image.
     * This feature is useful for conserving memory in applications that require a
     * relatively small sub image from a very large TIFF file. The specifications
     * for partial images are as follows:
     * </p>
     *
     * <pre>
     * TiffImagingParameters params = new TiffImagingParameters();
     * params.setSubImageX(x);
     * params.setSubImageY(y);
     * params.setSubImageWidth(width);
     * params.setSubImageHeight(height);
     * </pre>
     *
     * <p>
     * Note that the arguments x, y, width, and height must specify a valid
     * rectangular region that is fully contained within the source TIFF image.
     * </p>
     *
     * @param byteSource A valid instance of ByteSource
     * @param params     Optional instructions for special-handling or
     *                   interpretation of the input data (null objects are
     *                   permitted and must be supported by
     *                   implementations).
     * @return A valid instance of BufferedImage.
     * @throws ImagingException In the event that the specified content does not
     *                          conform to the format of the specific parser
     *                          implementation.
     * @throws IOException      In the event of unsuccessful read or access
     *                          operation.
     */
    @Override
    public BufferedImage getBufferedImage(final ByteSource byteSource, TiffImagingParameters params)
            throws ImagingException, IOException {
        if (params == null) {
            params = new TiffImagingParameters();
        }
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffReader reader = new TiffReader(params.isStrict());
        final TiffContents contents = reader.readFirstDirectory(byteSource, true, formatCompliance);
        final ByteOrder byteOrder = reader.getByteOrder();
        final TiffDirectory directory = contents.directories.get(0);
        final BufferedImage result = directory.getTiffImage(byteOrder, params);
        if (null == result) {
            throw new ImagingException("TIFF does not contain an image.");
        }
        return result;
    }

    protected BufferedImage getBufferedImage(final TiffDirectory directory, final ByteOrder byteOrder,
            final TiffImagingParameters params)
            throws ImagingException, IOException {
        final short compressionFieldValue;
        if (directory.findField(TiffTagConstants.TIFF_TAG_COMPRESSION) != null) {
            // Requirement: TIFF_TAG_COMPRESSION is not null.
            TiffCoverageLogger.getBufferedImagelogBranch_run(1);
            compressionFieldValue = directory.getFieldValue(TiffTagConstants.TIFF_TAG_COMPRESSION);
        } else {
            TiffCoverageLogger.getBufferedImagelogBranch_run(2);
            compressionFieldValue = TiffConstants.COMPRESSION_UNCOMPRESSED_1;
        }
        final int compression = 0xffff & compressionFieldValue;
        final int width = directory.getSingleFieldValue(TiffTagConstants.TIFF_TAG_IMAGE_WIDTH);
        final int height = directory.getSingleFieldValue(TiffTagConstants.TIFF_TAG_IMAGE_LENGTH);

        final Rectangle subImage = checkForSubImage(params);
        if (subImage != null) {
            // Check for valid subimage specification. The following checks
            // are consistent with BufferedImage.getSubimage()

            // Requirement: subImage is not null
            TiffCoverageLogger.getBufferedImagelogBranch_run(3);
            validateSubImage(subImage, width, height);
        
        }
        else{
            TiffCoverageLogger.getBufferedImagelogBranch_run(18);
        }

        int samplesPerPixel = 1;
        final TiffField samplesPerPixelField = directory.findField(TiffTagConstants.TIFF_TAG_SAMPLES_PER_PIXEL);
        if (samplesPerPixelField != null) {
            // Requirement: samplesPerPixelField is not null
            TiffCoverageLogger.getBufferedImagelogBranch_run(19);
            samplesPerPixel = samplesPerPixelField.getIntValue();
        }
        else {
            TiffCoverageLogger.getBufferedImagelogBranch_run(20);
        }
        int[] bitsPerSample = { 1 };
        int bitsPerPixel = samplesPerPixel;
        final TiffField bitsPerSampleField = directory.findField(TiffTagConstants.TIFF_TAG_BITS_PER_SAMPLE);
        if (bitsPerSampleField != null) {
            // Requirement: bitsPerSampleField is not null
            TiffCoverageLogger.getBufferedImagelogBranch_run(21);
            bitsPerSample = bitsPerSampleField.getIntArrayValue();
            bitsPerPixel = bitsPerSampleField.getIntValueOrArraySum();
        }
        else {
            TiffCoverageLogger.getBufferedImagelogBranch_run(22);
        }

        // int bitsPerPixel = getTagAsValueOrArraySum(entries,
        // TIFF_TAG_BITS_PER_SAMPLE);

        int predictor = -1;
        {
            // dumpOptionalNumberTag(entries, TIFF_TAG_FILL_ORDER);
            // dumpOptionalNumberTag(entries, TIFF_TAG_FREE_BYTE_COUNTS);
            // dumpOptionalNumberTag(entries, TIFF_TAG_FREE_OFFSETS);
            // dumpOptionalNumberTag(entries, TIFF_TAG_ORIENTATION);
            // dumpOptionalNumberTag(entries, TIFF_TAG_PLANAR_CONFIGURATION);
            final TiffField predictorField = directory.findField(TiffTagConstants.TIFF_TAG_PREDICTOR);
            if (null != predictorField) {
                // Requirement: TIFF_TAG_PREDICTOR is not null
                TiffCoverageLogger.getBufferedImagelogBranch_run(23);
                predictor = predictorField.getIntValueOrArraySum();
            }
            else{
                TiffCoverageLogger.getBufferedImagelogBranch_run(24);
            }
        }

        if (samplesPerPixel != bitsPerSample.length) {
            // Requirement: samplesPerPixel is not equal to bitsPerSample.length
            TiffCoverageLogger.getBufferedImagelogBranch_run(25);
            throw new ImagingException("Tiff: samplesPerPixel (" + samplesPerPixel + ")!=fBitsPerSample.length (" + bitsPerSample.length + ")");
        }
        else{
            TiffCoverageLogger.getBufferedImagelogBranch_run(26);
        }

        final int photometricInterpretation = 0xffff
                & directory.getFieldValue(TiffTagConstants.TIFF_TAG_PHOTOMETRIC_INTERPRETATION);

        boolean hasAlpha = false;
        boolean isAlphaPremultiplied = false;
        if (photometricInterpretation == TiffTagConstants.PHOTOMETRIC_INTERPRETATION_VALUE_RGB) {
            //  Requirement: If the photometric interpretation is RGB, the TIFF file must correctly handle
            // the presence of an alpha channel.
            TiffCoverageLogger.getBufferedImagelogBranch_run(27);
            if(samplesPerPixel == 4){

                TiffCoverageLogger.getBufferedImagelogBranch_run(28);
                final TiffField extraSamplesField = directory.findField(TiffTagConstants.TIFF_TAG_EXTRA_SAMPLES);
            if (extraSamplesField == null) {
                // this state is not defined in the TIFF specification
                // and so this code will interpret it as meaning that the
                // proper handling would be ARGB.

                 // Requirement: If the EXTRA_SAMPLES field is missing, assume the fourth channel is
                // an unassociated alpha channel and interpret the image as ARGB.
                TiffCoverageLogger.getBufferedImagelogBranch_run(29);
                hasAlpha = true;
                isAlphaPremultiplied = false;
            } else {
                TiffCoverageLogger.getBufferedImagelogBranch_run(30);
                processExtraSamples(extraSamplesField.getIntValue(), hasAlpha, isAlphaPremultiplied);
            }
        }else{
            TiffCoverageLogger.getBufferedImagelogBranch_run(34);
        }
        }else{
            TiffCoverageLogger.getBufferedImagelogBranch_run(35);
        }

        PhotometricInterpreter photometricInterpreter = params == null ? null
                : params.getCustomPhotometricInterpreter();
        if (photometricInterpreter == null) {
            // Requirement: If no custom photometric interpreter is provided in the parameters
            TiffCoverageLogger.getBufferedImagelogBranch_run(36);
            photometricInterpreter = getPhotometricInterpreter(directory, photometricInterpretation, bitsPerPixel, bitsPerSample, predictor, samplesPerPixel,
                    width, height);
        }
        else {

            TiffCoverageLogger.getBufferedImagelogBranch_run(37);
            
        }

        // Obtain the planar configuration
        final TiffField pcField = directory.findField(TiffTagConstants.TIFF_TAG_PLANAR_CONFIGURATION);
        final TiffPlanarConfiguration planarConfiguration = pcField == null ? TiffPlanarConfiguration.CHUNKY
                : TiffPlanarConfiguration.lenientValueOf(pcField.getIntValue());

        if (planarConfiguration == TiffPlanarConfiguration.PLANAR) {
            // currently, we support the non-interleaved (non-chunky)
            // option only in the case of a 24-bit RBG photometric interpreter
            // and for strips (not for tiles).

            // Requirement: Planar configuration 2 (PLANAR) is currently only supported for
            // 24-bit RGB images stored in strips 
            TiffCoverageLogger.getBufferedImagelogBranch_run(38);
            if (photometricInterpretation != TiffTagConstants.PHOTOMETRIC_INTERPRETATION_VALUE_RGB) {
                // Requirement: Only RGB images can use PLANAR configuration. If another format is detected,
                // an exception is raised.
                TiffCoverageLogger.getBufferedImagelogBranch_run(39);
                throw new ImagingException("For planar configuration 2, only 24 bit RGB is currently supported");
            }
            else if ( bitsPerPixel != 24){
                // Requirement: PLANAR configuration requires exactly 24 bits per pixel.
                TiffCoverageLogger.getBufferedImagelogBranch_run(40);
            }
            else {

                TiffCoverageLogger.getBufferedImagelogBranch_run(41);
            }
            if (null == directory.findField(TiffTagConstants.TIFF_TAG_STRIP_OFFSETS)) {
                // Requirement: PLANAR configuration is only supported for strip-based images, not tiled images.
                // If strip offsets are missing, throw an exception.
                TiffCoverageLogger.getBufferedImagelogBranch_run(42);
                throw new ImagingException("For planar configuration 2, only strips-organization is supported");
            }
            else{
                TiffCoverageLogger.getBufferedImagelogBranch_run(43);
            }
        }
        else{
            TiffCoverageLogger.getBufferedImagelogBranch_run(44);
        }

        final AbstractTiffImageData imageData = directory.getTiffImageData();

        final ImageDataReader dataReader = imageData.getDataReader(directory, photometricInterpreter, bitsPerPixel,
                bitsPerSample, predictor, samplesPerPixel,
                width, height, compression, planarConfiguration, byteOrder);

        final ImageBuilder iBuilder = dataReader.readImageData(subImage, hasAlpha, isAlphaPremultiplied);
        return iBuilder.getBufferedImage();
    }

    @Override
    public String getDefaultExtension() {
        return DEFAULT_EXTENSION;
    }

    @Override
    public TiffImagingParameters getDefaultParameters() {
        return new TiffImagingParameters();
    }

    @Override
    public FormatCompliance getFormatCompliance(final ByteSource byteSource) throws ImagingException, IOException {
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffImagingParameters params = new TiffImagingParameters();
        new TiffReader(params.isStrict()).readContents(byteSource, params, formatCompliance);
        return formatCompliance;
    }

    @Override
    public byte[] getIccProfileBytes(final ByteSource byteSource, final TiffImagingParameters params)
            throws ImagingException, IOException {
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffContents contents = new TiffReader(params != null && params.isStrict()).readFirstDirectory(byteSource,
                false, formatCompliance);
        final TiffDirectory directory = contents.directories.get(0);

        return directory.getFieldValue(TiffEpTagConstants.EXIF_TAG_INTER_COLOR_PROFILE, false);
    }

    @Override
    public ImageInfo getImageInfo(final ByteSource byteSource, final TiffImagingParameters params)
            throws ImagingException, IOException {
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffContents contents = new TiffReader(params != null && params.isStrict()).readDirectories(byteSource,
                false, formatCompliance);
        final TiffDirectory directory = contents.directories.get(0);

        final TiffField widthField = directory.findField(TiffTagConstants.TIFF_TAG_IMAGE_WIDTH, true);
        final TiffField heightField = directory.findField(TiffTagConstants.TIFF_TAG_IMAGE_LENGTH, true);

        if (widthField == null) {
            TiffCoverageLogger.logBranch_run_getImageInfo(1);
            throw new ImagingException("TIFF image missing size info.");
        } else if (heightField == null) {
            TiffCoverageLogger.logBranch_run_getImageInfo(2);
            throw new ImagingException("TIFF image missing size info.");
        } else {
            TiffCoverageLogger.logBranch_run_getImageInfo(3);
        }

        final int height = heightField.getIntValue();
        final int width = widthField.getIntValue();

        final TiffField resolutionUnitField = directory.findField(TiffTagConstants.TIFF_TAG_RESOLUTION_UNIT);
        int resolutionUnit = 2; // Inch
        if (resolutionUnitField != null) {
            TiffCoverageLogger.logBranch_run_getImageInfo(4);
            if (resolutionUnitField.getValue() != null) {
                TiffCoverageLogger.logBranch_run_getImageInfo(5);
                resolutionUnit = resolutionUnitField.getIntValue();
            } else {
                TiffCoverageLogger.logBranch_run_getImageInfo(6);
            }
        } else {
            TiffCoverageLogger.logBranch_run_getImageInfo(7);
        }

        double unitsPerInch = -1;
        switch (resolutionUnit) {
            case 1:
                TiffCoverageLogger.logBranch_run_getImageInfo(8);
                break;
            case 2: // Inch
                TiffCoverageLogger.logBranch_run_getImageInfo(9);
                unitsPerInch = 1.0;
                break;
            case 3: // Centimeter
                TiffCoverageLogger.logBranch_run_getImageInfo(10);
                unitsPerInch = 2.54;
                break;
            default:
                TiffCoverageLogger.logBranch_run_getImageInfo(11);
                break;
        }

        int physicalWidthDpi = -1;
        float physicalWidthInch = -1;
        int physicalHeightDpi = -1;
        float physicalHeightInch = -1;

        if (unitsPerInch > 0) {
            TiffCoverageLogger.logBranch_run_getImageInfo(12);
            final TiffField xResolutionField = directory.findField(TiffTagConstants.TIFF_TAG_XRESOLUTION);
            final TiffField yResolutionField = directory.findField(TiffTagConstants.TIFF_TAG_YRESOLUTION);

            if (xResolutionField != null) {
                TiffCoverageLogger.logBranch_run_getImageInfo(13);
                if (xResolutionField.getValue() != null) {
                    TiffCoverageLogger.logBranch_run_getImageInfo(14);
                    final double xResolutionPixelsPerUnit = xResolutionField.getDoubleValue();
                    physicalWidthDpi = (int) Math.round(xResolutionPixelsPerUnit * unitsPerInch);
                    physicalWidthInch = (float) (width / (xResolutionPixelsPerUnit * unitsPerInch));
                } else {
                    TiffCoverageLogger.logBranch_run_getImageInfo(15);
                }
            } else {
                TiffCoverageLogger.logBranch_run_getImageInfo(16);
            }
            if (yResolutionField != null) {
                TiffCoverageLogger.logBranch_run_getImageInfo(17);
                if (yResolutionField.getValue() != null) {
                    TiffCoverageLogger.logBranch_run_getImageInfo(18);
                    final double yResolutionPixelsPerUnit = yResolutionField.getDoubleValue();
                    physicalHeightDpi = (int) Math.round(yResolutionPixelsPerUnit * unitsPerInch);
                    physicalHeightInch = (float) (height / (yResolutionPixelsPerUnit * unitsPerInch));
                } else {
                    TiffCoverageLogger.logBranch_run_getImageInfo(19);
                }
            } else {
                TiffCoverageLogger.logBranch_run_getImageInfo(20);
            }
        } else {
            TiffCoverageLogger.logBranch_run_getImageInfo(21);
        }

        final TiffField bitsPerSampleField = directory.findField(TiffTagConstants.TIFF_TAG_BITS_PER_SAMPLE);

        int bitsPerSample = 1;
        if (bitsPerSampleField != null) {
            TiffCoverageLogger.logBranch_run_getImageInfo(22);
            if (bitsPerSampleField.getValue() != null) {
                TiffCoverageLogger.logBranch_run_getImageInfo(23);
                bitsPerSample = bitsPerSampleField.getIntValueOrArraySum();
            } else {
                TiffCoverageLogger.logBranch_run_getImageInfo(24);
            }
        } else {
            TiffCoverageLogger.logBranch_run_getImageInfo(25);
        }

        final int bitsPerPixel = bitsPerSample; // assume grayscale;
        // dunno if this handles colormapped images correctly.

        final List<String> comments = Allocator.arrayList(directory.size());
        for (final TiffField field : directory) {
            TiffCoverageLogger.logBranch_run_getImageInfo(26);
            final String comment = field.toString();
            comments.add(comment);
        }
        TiffCoverageLogger.logBranch_run_getImageInfo(27);

        final ImageFormat format = ImageFormats.TIFF;
        final String formatName = "TIFF Tag-based Image File Format";
        final String mimeType = "image/tiff";
        final int numberOfImages = contents.directories.size();
        // not accurate ... only reflects first
        final boolean progressive = false;
        // is TIFF ever interlaced/progressive?

        final String formatDetails = "TIFF v." + contents.header.tiffVersion;

        boolean transparent = false; // TODO: wrong
        boolean usesPalette = false;
        final TiffField colorMapField = directory.findField(TiffTagConstants.TIFF_TAG_COLOR_MAP);
        if (colorMapField != null) {
            TiffCoverageLogger.logBranch_run_getImageInfo(28);
            usesPalette = true;
        } else {
            TiffCoverageLogger.logBranch_run_getImageInfo(29);
        }

        final int photoInterp = 0xffff & directory.getFieldValue(TiffTagConstants.TIFF_TAG_PHOTOMETRIC_INTERPRETATION);
        final TiffField extraSamplesField = directory.findField(TiffTagConstants.TIFF_TAG_EXTRA_SAMPLES);
        final int extraSamples;
        if (extraSamplesField == null) {
            TiffCoverageLogger.logBranch_run_getImageInfo(30);
            extraSamples = 0; // no extra samples value
        } else {
            TiffCoverageLogger.logBranch_run_getImageInfo(31);
            extraSamples = extraSamplesField.getIntValue();
        }
        final TiffField samplesPerPixelField = directory.findField(TiffTagConstants.TIFF_TAG_SAMPLES_PER_PIXEL);
        final int samplesPerPixel;
        if (samplesPerPixelField == null) {
            TiffCoverageLogger.logBranch_run_getImageInfo(32);
            samplesPerPixel = 1;
        } else {
            TiffCoverageLogger.logBranch_run_getImageInfo(33);
            samplesPerPixel = samplesPerPixelField.getIntValue();
        }

        final ImageInfo.ColorType colorType;
        switch (photoInterp) {
            case TiffTagConstants.PHOTOMETRIC_INTERPRETATION_VALUE_BLACK_IS_ZERO:
                TiffCoverageLogger.logBranch_run_getImageInfo(34);
            case TiffTagConstants.PHOTOMETRIC_INTERPRETATION_VALUE_WHITE_IS_ZERO:
                TiffCoverageLogger.logBranch_run_getImageInfo(35);
                // the ImageInfo.ColorType enumeration does not distinguish
                // between monotone white is zero or black is zero
                colorType = ImageInfo.ColorType.BW;
                break;
            case TiffTagConstants.PHOTOMETRIC_INTERPRETATION_VALUE_RGB:
                TiffCoverageLogger.logBranch_run_getImageInfo(36);
                colorType = ImageInfo.ColorType.RGB;
                // even if 4 samples per pixel are included, TIFF
                // doesn't specify transparent unless the optional "extra samples"
                // field is supplied with a non-zero value
                transparent = samplesPerPixel == 4 && extraSamples != 0;
                break;
            case TiffTagConstants.PHOTOMETRIC_INTERPRETATION_VALUE_RGB_PALETTE:
                TiffCoverageLogger.logBranch_run_getImageInfo(37);
                colorType = ImageInfo.ColorType.RGB;
                usesPalette = true;
                break;
            case TiffTagConstants.PHOTOMETRIC_INTERPRETATION_VALUE_CMYK:
                TiffCoverageLogger.logBranch_run_getImageInfo(38);
                colorType = ImageInfo.ColorType.CMYK;
                break;
            case TiffTagConstants.PHOTOMETRIC_INTERPRETATION_VALUE_YCB_CR:
                TiffCoverageLogger.logBranch_run_getImageInfo(39);
                colorType = ImageInfo.ColorType.YCbCr;
                break;
            default:
                TiffCoverageLogger.logBranch_run_getImageInfo(40);
                colorType = ImageInfo.ColorType.UNKNOWN;
        }

        final short compressionFieldValue;
        if (directory.findField(TiffTagConstants.TIFF_TAG_COMPRESSION) != null) {
            TiffCoverageLogger.logBranch_run_getImageInfo(41);
            compressionFieldValue = directory.getFieldValue(TiffTagConstants.TIFF_TAG_COMPRESSION);
        } else {
            TiffCoverageLogger.logBranch_run_getImageInfo(42);
            compressionFieldValue = TiffConstants.COMPRESSION_UNCOMPRESSED_1;
        }
        final int compression = 0xffff & compressionFieldValue;
        final ImageInfo.CompressionAlgorithm compressionAlgorithm;

        switch (compression) {
            case TiffConstants.COMPRESSION_UNCOMPRESSED_1:
                TiffCoverageLogger.logBranch_run_getImageInfo(43);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.NONE;
                break;
            case TiffConstants.COMPRESSION_CCITT_1D:
                TiffCoverageLogger.logBranch_run_getImageInfo(44);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.CCITT_1D;
                break;
            case TiffConstants.COMPRESSION_CCITT_GROUP_3:
                TiffCoverageLogger.logBranch_run_getImageInfo(45);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.CCITT_GROUP_3;
                break;
            case TiffConstants.COMPRESSION_CCITT_GROUP_4:
                TiffCoverageLogger.logBranch_run_getImageInfo(46);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.CCITT_GROUP_4;
                break;
            case TiffConstants.COMPRESSION_LZW:
                TiffCoverageLogger.logBranch_run_getImageInfo(47);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.LZW;
                break;
            case TiffConstants.COMPRESSION_JPEG_OBSOLETE:
                TiffCoverageLogger.logBranch_run_getImageInfo(48);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.JPEG_TIFF_OBSOLETE;
                break;
            case TiffConstants.COMPRESSION_JPEG:
                TiffCoverageLogger.logBranch_run_getImageInfo(49);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.JPEG;
                break;
            case TiffConstants.COMPRESSION_UNCOMPRESSED_2:
                TiffCoverageLogger.logBranch_run_getImageInfo(50);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.NONE;
                break;
            case TiffConstants.COMPRESSION_PACKBITS:
                TiffCoverageLogger.logBranch_run_getImageInfo(51);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.PACKBITS;
                break;
            case TiffConstants.COMPRESSION_DEFLATE_PKZIP:
                TiffCoverageLogger.logBranch_run_getImageInfo(52);
            case TiffConstants.COMPRESSION_DEFLATE_ADOBE:
                TiffCoverageLogger.logBranch_run_getImageInfo(53);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.DEFLATE;
                break;
            default:
                TiffCoverageLogger.logBranch_run_getImageInfo(54);
                compressionAlgorithm = ImageInfo.CompressionAlgorithm.UNKNOWN;
                break;
        }

        return new ImageInfo(formatDetails, bitsPerPixel, comments, format, formatName, height, mimeType,
                numberOfImages, physicalHeightDpi, physicalHeightInch,
                physicalWidthDpi, physicalWidthInch, width, progressive, transparent, usesPalette, colorType,
                compressionAlgorithm);
    }

    @Override
    public Dimension getImageSize(final ByteSource byteSource, final TiffImagingParameters params)
            throws ImagingException, IOException {
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffContents contents = new TiffReader(params != null && params.isStrict()).readFirstDirectory(byteSource,
                false, formatCompliance);
        final TiffDirectory directory = contents.directories.get(0);

        final TiffField widthField = directory.findField(TiffTagConstants.TIFF_TAG_IMAGE_WIDTH, true);
        final TiffField heightField = directory.findField(TiffTagConstants.TIFF_TAG_IMAGE_LENGTH, true);

        if (widthField == null || heightField == null) {
            throw new ImagingException("TIFF image missing size info.");
        }

        final int height = heightField.getIntValue();
        final int width = widthField.getIntValue();

        return new Dimension(width, height);
    }

    @Override
    public ImageMetadata getMetadata(final ByteSource byteSource, TiffImagingParameters params)
            throws ImagingException, IOException {
        if (params == null) {
            params = getDefaultParameters();
        }
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffReader tiffReader = new TiffReader(params.isStrict());
        final TiffContents contents = tiffReader.readContents(byteSource, params, formatCompliance);

        final List<TiffDirectory> directories = contents.directories;

        final TiffImageMetadata result = new TiffImageMetadata(contents);

        for (final TiffDirectory dir : directories) {
            final TiffImageMetadata.Directory metadataDirectory = new TiffImageMetadata.Directory(
                    tiffReader.getByteOrder(), dir);

            final List<TiffField> entries = dir.getDirectoryEntries();

            for (final TiffField entry : entries) {
                metadataDirectory.add(entry);
            }

            result.add(metadataDirectory);
        }

        return result;
    }

    @Override
    public String getName() {
        return "Tiff-Custom";
    }

    private PhotometricInterpreter getPhotometricInterpreter(final TiffDirectory directory,
            final int photometricInterpretation, final int bitsPerPixel,
            final int[] bitsPerSample, final int predictor, final int samplesPerPixel, final int width,
            final int height) throws ImagingException {
        switch (photometricInterpretation) {
            case 0:
            case 1:
                final boolean invert = photometricInterpretation == 0;

                return new PhotometricInterpreterBiLevel(samplesPerPixel, bitsPerSample, predictor, width, height,
                        invert);
            case 3: {
                // Palette
                final int[] colorMap = directory.findField(TiffTagConstants.TIFF_TAG_COLOR_MAP, true)
                        .getIntArrayValue();

                final int expectedColormapSize = 3 * (1 << bitsPerPixel);

                if (colorMap.length != expectedColormapSize) {
                    throw new ImagingException("Tiff: fColorMap.length (" + colorMap.length
                            + ") != expectedColormapSize (" + expectedColormapSize + ")");
                }

                return new PhotometricInterpreterPalette(samplesPerPixel, bitsPerSample, predictor, width, height,
                        colorMap);
            }
            case 2: // RGB
                return new PhotometricInterpreterRgb(samplesPerPixel, bitsPerSample, predictor, width, height);
            case 5: // CMYK
                return new PhotometricInterpreterCmyk(samplesPerPixel, bitsPerSample, predictor, width, height);
            case 6: {
                // final double[] yCbCrCoefficients = directory.findField(
                // TiffTagConstants.TIFF_TAG_YCBCR_COEFFICIENTS, true)
                // .getDoubleArrayValue();
                //
                // final int[] yCbCrPositioning = directory.findField(
                // TiffTagConstants.TIFF_TAG_YCBCR_POSITIONING, true)
                // .getIntArrayValue();
                // final int[] yCbCrSubSampling = directory.findField(
                // TiffTagConstants.TIFF_TAG_YCBCR_SUB_SAMPLING, true)
                // .getIntArrayValue();
                //
                // final double[] referenceBlackWhite = directory.findField(
                // TiffTagConstants.TIFF_TAG_REFERENCE_BLACK_WHITE, true)
                // .getDoubleArrayValue();

                return new PhotometricInterpreterYCbCr(samplesPerPixel, bitsPerSample, predictor, width, height);
            }

            case 8:
                return new PhotometricInterpreterCieLab(samplesPerPixel, bitsPerSample, predictor, width, height);

            case 32844:
            case 32845: {
                // final boolean yonly = (photometricInterpretation == 32844);
                return new PhotometricInterpreterLogLuv(samplesPerPixel, bitsPerSample, predictor, width, height);
            }

            default:
                throw new ImagingException("TIFF: Unknown fPhotometricInterpretation: " + photometricInterpretation);
        }
    }

    /**
     * Reads the content of a TIFF file that contains numerical data samples rather
     * than image-related pixels.
     * <p>
     * If desired, sub-image data can be read from the file by using a Java
     * {@code TiffImagingParameters} instance to specify the subsection of the image
     * that
     * is required. The following code illustrates the approach:
     *
     * <pre>
     * int x; // coordinate (column) of corner of sub-image
     * int y; // coordinate (row) of corner of sub-image
     * int width; // width of sub-image
     * int height; // height of sub-image
     *
     * TiffImagingParameters params = new TiffImagingParameters();
     * params.setSubImageX(x);
     * params.setSubImageY(y);
     * params.setSubImageWidth(width);
     * params.setSubImageHeight(height);
     * TiffRasterData raster = readFloatingPointRasterData(directory, byteOrder, params);
     * </pre>
     *
     * @param directory the TIFF directory pointing to the data to be extracted
     *                  (TIFF files may contain multiple directories)
     * @param byteOrder the byte order of the data to be extracted
     * @param params    an optional parameter object instance
     * @return a valid instance
     * @throws ImagingException in the event of incompatible or malformed data
     * @throws IOException      in the event of an I/O error
     */
    TiffRasterData getRasterData(final TiffDirectory directory, final ByteOrder byteOrder, TiffImagingParameters params) throws ImagingException, IOException {

        
        if (params == null) {
            TiffCoverageLogger.logBranch_run(1);
            params = getDefaultParameters();
        } else {
            TiffCoverageLogger.logBranch_run(2);
        }

        final short[] sSampleFmt = directory.getFieldValue(TiffTagConstants.TIFF_TAG_SAMPLE_FORMAT, true);
        if (sSampleFmt == null) {
            TiffCoverageLogger.logBranch_run(3);
            throw new ImagingException("Directory does not specify numeric raster data");
        } else if (sSampleFmt.length < 1) {
            TiffCoverageLogger.logBranch_run(4);
            throw new ImagingException("Directory does not specify numeric raster data");
        } else {
            TiffCoverageLogger.logBranch_run(5);
        }

        int samplesPerPixel = 1;
        final TiffField samplesPerPixelField = directory.findField(TiffTagConstants.TIFF_TAG_SAMPLES_PER_PIXEL);
        if (samplesPerPixelField != null) {
            TiffCoverageLogger.logBranch_run(6);
            samplesPerPixel = samplesPerPixelField.getIntValue();
        } else {
            TiffCoverageLogger.logBranch_run(7);
        }

        int[] bitsPerSample = { 1 };
        int bitsPerPixel = samplesPerPixel;
        final TiffField bitsPerSampleField = directory.findField(TiffTagConstants.TIFF_TAG_BITS_PER_SAMPLE);
        if (bitsPerSampleField != null) {
            TiffCoverageLogger.logBranch_run(8);
            bitsPerSample = bitsPerSampleField.getIntArrayValue();
            bitsPerPixel = bitsPerSampleField.getIntValueOrArraySum();
        } else {
            TiffCoverageLogger.logBranch_run(9);
        }

        final short compressionFieldValue;
        if (directory.findField(TiffTagConstants.TIFF_TAG_COMPRESSION) != null) {
            TiffCoverageLogger.logBranch_run(10);
            compressionFieldValue = directory.getFieldValue(TiffTagConstants.TIFF_TAG_COMPRESSION);
        } else {
            TiffCoverageLogger.logBranch_run(11);
            compressionFieldValue = TiffConstants.COMPRESSION_UNCOMPRESSED_1;
        }
        final int compression = 0xffff & compressionFieldValue;

        final int width = directory.getSingleFieldValue(TiffTagConstants.TIFF_TAG_IMAGE_WIDTH);
        final int height = directory.getSingleFieldValue(TiffTagConstants.TIFF_TAG_IMAGE_LENGTH);

        Rectangle subImage = checkForSubImage(params);
        if (subImage != null) {
            validateSubImage(subImage, width, height);
            TiffCoverageLogger.logBranch_run(12);

            // if the subimage is just the same thing as the whole
            // image, suppress the subimage processing
            if (subImage.x == 0) {
                TiffCoverageLogger.logBranch_run(27);
                if (subImage.y == 0) {
                    TiffCoverageLogger.logBranch_run(28);
                    if (subImage.width == width) {
                        TiffCoverageLogger.logBranch_run(29);
                        if (subImage.height == height) {
                            TiffCoverageLogger.logBranch_run(30);
                            subImage = null;
                        } else {
                            TiffCoverageLogger.logBranch_run(31);
                        }
                    } else {
                        TiffCoverageLogger.logBranch_run(32);
                    }
                } else {
                    TiffCoverageLogger.logBranch_run(33);

                }
            } else {
                TiffCoverageLogger.logBranch_run(34);
            }
        } else {
            TiffCoverageLogger.logBranch_run(35);
        }

        // int bitsPerPixel = getTagAsValueOrArraySum(entries,
        // TIFF_TAG_BITS_PER_SAMPLE);
        int predictor = -1;
        {
            // dumpOptionalNumberTag(entries, TIFF_TAG_FILL_ORDER);
            // dumpOptionalNumberTag(entries, TIFF_TAG_FREE_BYTE_COUNTS);
            // dumpOptionalNumberTag(entries, TIFF_TAG_FREE_OFFSETS);
            // dumpOptionalNumberTag(entries, TIFF_TAG_ORIENTATION);
            // dumpOptionalNumberTag(entries, TIFF_TAG_PLANAR_CONFIGURATION);
            final TiffField predictorField = directory.findField(TiffTagConstants.TIFF_TAG_PREDICTOR);
            if (null != predictorField) {
                TiffCoverageLogger.logBranch_run(36);
                predictor = predictorField.getIntValueOrArraySum();
            } else {
                TiffCoverageLogger.logBranch_run(37);
            }
        }

        // Obtain the planar configuration
        final TiffField pcField = directory.findField(TiffTagConstants.TIFF_TAG_PLANAR_CONFIGURATION);
        final TiffPlanarConfiguration planarConfiguration;
        if (pcField == null) {
            TiffCoverageLogger.logBranch_run(38);
            planarConfiguration = TiffPlanarConfiguration.CHUNKY;
        } else {
            TiffCoverageLogger.logBranch_run(39);
            planarConfiguration = TiffPlanarConfiguration.lenientValueOf(pcField.getIntValue());
        }

        if (sSampleFmt[0] == TiffTagConstants.SAMPLE_FORMAT_VALUE_IEEE_FLOATING_POINT) {
            TiffCoverageLogger.logBranch_run(40);
            if (bitsPerSample[0] != 32) {
                TiffCoverageLogger.logBranch_run(41);
                if (bitsPerSample[0] != 64) {
                    TiffCoverageLogger.logBranch_run(42);
                    throw new ImagingException(
                            "TIFF floating-point data uses unsupported bits-per-sample: " + bitsPerSample[0]);
                } else {
                    TiffCoverageLogger.logBranch_run(43);
                }
            } else {
                TiffCoverageLogger.logBranch_run(44);
            }

            if (predictor != -1) {
                TiffCoverageLogger.logBranch_run(45);
                if (predictor != TiffTagConstants.PREDICTOR_VALUE_NONE) {
                    TiffCoverageLogger.logBranch_run(46);
                    if (predictor != TiffTagConstants.PREDICTOR_VALUE_FLOATING_POINT_DIFFERENCING) {
                        TiffCoverageLogger.logBranch_run(47);
                        throw new ImagingException(
                                "TIFF floating-point data uses unsupported horizontal-differencing predictor");
                    } else {
                        TiffCoverageLogger.logBranch_run(48);
                    }
                } else {
                    TiffCoverageLogger.logBranch_run(49);
                }
            } else {
                TiffCoverageLogger.logBranch_run(50);
            }
        } else if (sSampleFmt[0] == TiffTagConstants.SAMPLE_FORMAT_VALUE_TWOS_COMPLEMENT_SIGNED_INTEGER) {
            TiffCoverageLogger.logBranch_run(51);

            if (samplesPerPixel != 1) {
                TiffCoverageLogger.logBranch_run(52);
                throw new ImagingException("TIFF integer data uses unsupported samples per pixel: " + samplesPerPixel);
            } else {
                TiffCoverageLogger.logBranch_run(53);
            }

            if (bitsPerPixel != 16) {
                TiffCoverageLogger.logBranch_run(54);
                if (bitsPerPixel != 32) {
                    TiffCoverageLogger.logBranch_run(55);
                    throw new ImagingException("TIFF integer data uses unsupported bits-per-pixel: " + bitsPerPixel);
                } else {
                    TiffCoverageLogger.logBranch_run(56);
                }
            } else {
                TiffCoverageLogger.logBranch_run(57);
            }

            if (predictor != -1) {
                TiffCoverageLogger.logBranch_run(58);
                if (predictor != TiffTagConstants.PREDICTOR_VALUE_NONE) {
                    TiffCoverageLogger.logBranch_run(59);
                    if (predictor != TiffTagConstants.PREDICTOR_VALUE_HORIZONTAL_DIFFERENCING) {
                        TiffCoverageLogger.logBranch_run(60);
                        throw new ImagingException(
                                "TIFF integer data uses unsupported horizontal-differencing predictor");
                    } else {
                        TiffCoverageLogger.logBranch_run(61);
                    }
                } else {
                    TiffCoverageLogger.logBranch_run(62);
                }
            } else {
                TiffCoverageLogger.logBranch_run(63);
            }
        } else {
            TiffCoverageLogger.logBranch_run(64);
            throw new ImagingException("TIFF does not provide a supported raster-data format");
        }

        // The photometric interpreter is not used, but the image-based
        // data reader classes require one. So we create a dummy interpreter.
        final PhotometricInterpreter photometricInterpreter = new PhotometricInterpreterBiLevel(samplesPerPixel,
                bitsPerSample, predictor, width, height,
                false);

        final AbstractTiffImageData imageData = directory.getTiffImageData();

        final ImageDataReader dataReader = imageData.getDataReader(directory, photometricInterpreter, bitsPerPixel,
                bitsPerSample, predictor, samplesPerPixel,
                width, height, compression, planarConfiguration, byteOrder);

        return dataReader.readRasterData(subImage);
    }

    @Override
    public String getXmpXml(final ByteSource byteSource, XmpImagingParameters<TiffImagingParameters> params)
            throws ImagingException, IOException {
        if (params == null) {
            params = new XmpImagingParameters<>();
        }
        final FormatCompliance formatCompliance = FormatCompliance.getDefault();
        final TiffContents contents = new TiffReader(params.isStrict()).readDirectories(byteSource, false,
                formatCompliance);
        final TiffDirectory directory = contents.directories.get(0);

        final byte[] bytes = directory.getFieldValue(TiffTagConstants.TIFF_TAG_XMP, false);
        if (bytes == null) {
            return null;
        }

        // segment data is UTF-8 encoded xml.
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void writeImage(final BufferedImage src, final OutputStream os, TiffImagingParameters params)
            throws ImagingException, IOException {
        if (params == null) {
            params = new TiffImagingParameters();
        }
        new TiffImageWriterLossy().writeImage(src, os, params);
    }

    /**
     * Check for valid subimage specification. The following checks are consistent with BufferedImage.getSubimage().
     * Validates that the specified subImage is within the bounds [0..width/height].
     * @param subImage A subImage rectangle
     * @param width    The full image width
     * @param height   The full image height
     * @return The same subImage if valid, or null if no subImage was requested
     * @throws ImagingException if the subImage is invalid (e.g., out of bounds)
     */
    public static void validateSubImage(final Rectangle subImage, final int width, final int height) throws ImagingException {
        //if (subImage.width <= 0) throw new ImagingException("Negative or zero subimage width.");
        //
        //if (subImage.height <= 0) throw new ImagingException("Negative or zero subimage height.");
        //
        //if (subImage.x < 0 || subImage.x >= width) throw new ImagingException("Subimage x is outside raster.");
        //
        //if (subImage.y < 0 || subImage.y >= height) throw new ImagingException("Subimage y is outside raster.");
        //
        //if (subImage.x + subImage.width > width) throw new ImagingException("Subimage (x+width) is outside raster.");
        //
        //if (subImage.y + subImage.height > height) throw new ImagingException("Subimage (y+height) is outside raster.");
            // Check for valid subimage specification. The following checks
            // are consistent with BufferedImage.getSubimage()
            if (subImage.width <= 0) {
                TiffCoverageLogger.logBranch_run(13);
                TiffCoverageLogger.getBufferedImagelogBranch_run(4);
                throw new ImagingException("Negative or zero subimage width.");
            }
            else{
                TiffCoverageLogger.logBranch_run(14);
                TiffCoverageLogger.getBufferedImagelogBranch_run(5);
            }

            if (subImage.height <= 0) {
                TiffCoverageLogger.logBranch_run(15);
                TiffCoverageLogger.getBufferedImagelogBranch_run(6);
                throw new ImagingException("Negative or zero subimage height.");
            }
            else{
                TiffCoverageLogger.logBranch_run(16);
                TiffCoverageLogger.getBufferedImagelogBranch_run(7);
            }

            if (subImage.x < 0) {
                TiffCoverageLogger.logBranch_run(17);
                TiffCoverageLogger.getBufferedImagelogBranch_run(8);
                throw new ImagingException("Subimage x is outside raster.");
            }
            else if(subImage.x >= width){
                TiffCoverageLogger.logBranch_run(18);
                TiffCoverageLogger.getBufferedImagelogBranch_run(9);
                throw new ImagingException("Subimage x is outside raster.");
            }
            else{
                TiffCoverageLogger.logBranch_run(19);
                TiffCoverageLogger.getBufferedImagelogBranch_run(10);
            }

            if (subImage.x + subImage.width > width) {
                TiffCoverageLogger.logBranch_run(20);
                TiffCoverageLogger.getBufferedImagelogBranch_run(11);
                throw new ImagingException("Subimage (x+width) is outside raster.");
            }
            else{
                TiffCoverageLogger.logBranch_run(21);
                TiffCoverageLogger.getBufferedImagelogBranch_run(12);
            }

            if (subImage.y < 0) {
                TiffCoverageLogger.logBranch_run(22);
                TiffCoverageLogger.getBufferedImagelogBranch_run(13);
                throw new ImagingException("Subimage y is outside raster.");
            }
            else if(subImage.y >= height){
                TiffCoverageLogger.logBranch_run(23);
                TiffCoverageLogger.getBufferedImagelogBranch_run(14);
                throw new ImagingException("Subimage y is outside raster.");
            }
            else{
                TiffCoverageLogger.logBranch_run(24);
                TiffCoverageLogger.getBufferedImagelogBranch_run(15);
            }

            if (subImage.y + subImage.height > height) {
                TiffCoverageLogger.logBranch_run(25);
                TiffCoverageLogger.getBufferedImagelogBranch_run(16);
                throw new ImagingException("Subimage (y+height) is outside raster.");
            }
            else{
                TiffCoverageLogger.logBranch_run(26);
                TiffCoverageLogger.getBufferedImagelogBranch_run(17);

            }
    }       

    private void processExtraSamples(final int extraSamplesField,boolean hasAlpha,boolean isAlphaPremultiplied) {
        final int extraSamplesValue = extraSamplesField;
        switch (extraSamplesValue) {
            case TiffTagConstants.EXTRA_SAMPLE_UNASSOCIATED_ALPHA:
                // Requirement: An unassociated alpha channel means the alpha values are not premultiplied.
                TiffCoverageLogger.getBufferedImagelogBranch_run(31);
                hasAlpha = true;
                isAlphaPremultiplied = false;
                break;
            case TiffTagConstants.EXTRA_SAMPLE_ASSOCIATED_ALPHA:
                // Requirement: An associated alpha channel means the color values are premultiplied by alpha.
                TiffCoverageLogger.getBufferedImagelogBranch_run(32);
                hasAlpha = true;
                isAlphaPremultiplied = true;
                break;
            case 0:
            default:
                // Requirement: If the extra samples value is 0 or an undefined value, assume no alpha channel.
                TiffCoverageLogger.getBufferedImagelogBranch_run(33);
                hasAlpha = false;
                isAlphaPremultiplied = false;
                break;
        }
    }
}

