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
package org.apache.commons.imaging.formats.gif;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.imaging.AbstractImageParser;
import org.apache.commons.imaging.FormatCompliance;
import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.ImageFormats;
import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.bytesource.ByteSource;
import org.apache.commons.imaging.common.AbstractBinaryOutputStream;
import org.apache.commons.imaging.common.Allocator;
import org.apache.commons.imaging.common.BinaryFunctions;
import org.apache.commons.imaging.common.ImageBuilder;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.common.XmpEmbeddable;
import org.apache.commons.imaging.common.XmpImagingParameters;
import org.apache.commons.imaging.mylzw.MyLzwCompressor;
import org.apache.commons.imaging.mylzw.MyLzwDecompressor;
import org.apache.commons.imaging.palette.Palette;
import org.apache.commons.imaging.palette.PaletteFactory;

public class GifImageParser extends AbstractImageParser<GifImagingParameters> implements XmpEmbeddable<GifImagingParameters> {

    private static final Logger LOGGER = Logger.getLogger(GifImageParser.class.getName());

    private static final String DEFAULT_EXTENSION = ImageFormats.GIF.getDefaultExtension();
    private static final String[] ACCEPTED_EXTENSIONS = ImageFormats.GIF.getExtensions();
    private static final byte[] GIF_HEADER_SIGNATURE = { 71, 73, 70 };
    private static final int EXTENSION_CODE = 0x21;
    private static final int IMAGE_SEPARATOR = 0x2C;
    private static final int GRAPHIC_CONTROL_EXTENSION = EXTENSION_CODE << 8 | 0xf9;
    private static final int COMMENT_EXTENSION = 0xfe;
    private static final int PLAIN_TEXT_EXTENSION = 0x01;
    private static final int XMP_EXTENSION = 0xff;
    private static final int TERMINATOR_BYTE = 0x3b;
    private static final int APPLICATION_EXTENSION_LABEL = 0xff;
    private static final int XMP_COMPLETE_CODE = EXTENSION_CODE << 8 | XMP_EXTENSION;
    private static final int LOCAL_COLOR_TABLE_FLAG_MASK = 1 << 7;
    private static final int INTERLACE_FLAG_MASK = 1 << 6;
    private static final int SORT_FLAG_MASK = 1 << 5;
    private static final byte[] XMP_APPLICATION_ID_AND_AUTH_CODE = { 0x58, // X
            0x4D, // M
            0x50, // P
            0x20, //
            0x44, // D
            0x61, // a
            0x74, // t
            0x61, // a
            0x58, // X
            0x4D, // M
            0x50, // P
    };

    // Made internal for testability.
    static DisposalMethod createDisposalMethodFromIntValue(final int value) throws ImagingException {
        switch (value) {
        case 0:
            return DisposalMethod.UNSPECIFIED;
        case 1:
            return DisposalMethod.DO_NOT_DISPOSE;
        case 2:
            return DisposalMethod.RESTORE_TO_BACKGROUND;
        case 3:
            return DisposalMethod.RESTORE_TO_PREVIOUS;
        case 4:
            return DisposalMethod.TO_BE_DEFINED_1;
        case 5:
            return DisposalMethod.TO_BE_DEFINED_2;
        case 6:
            return DisposalMethod.TO_BE_DEFINED_3;
        case 7:
            return DisposalMethod.TO_BE_DEFINED_4;
        default:
            throw new ImagingException("GIF: Invalid parsing of disposal method");
        }
    }

    /**
     * Constructs a new instance with the little-endian byte order.
     */
    public GifImageParser() {
        super(ByteOrder.LITTLE_ENDIAN);
    }

    private int convertColorTableSize(final int tableSize) {
        return 3 * simplePow(2, tableSize + 1);
    }

    @Override
    public boolean dumpImageFile(final PrintWriter pw, final ByteSource byteSource) throws ImagingException, IOException {
        pw.println("gif.dumpImageFile");

        final ImageInfo imageData = getImageInfo(byteSource);
        if (imageData == null) {
            return false;
        }

        imageData.toString(pw, "");

        final GifImageContents blocks = readFile(byteSource, false);

        pw.println("gif.blocks: " + blocks.blocks.size());
        for (int i = 0; i < blocks.blocks.size(); i++) {
            final GifBlock gifBlock = blocks.blocks.get(i);
            this.debugNumber(pw, "\t" + i + " (" + gifBlock.getClass().getName() + ")", gifBlock.blockCode, 4);
        }

        pw.println("");

        return true;
    }

    /**
     * See {@link GifImageParser#readBlocks} for reference how the blocks are created. They should match the code we are giving here, returning the correct
     * class type. Internal only.
     */
    @SuppressWarnings("unchecked")
    private <T extends GifBlock> List<T> findAllBlocks(final List<GifBlock> blocks, final int code) {
        final List<T> filteredBlocks = new ArrayList<>();
        for (final GifBlock gifBlock : blocks) {
            if (gifBlock.blockCode == code) {
                filteredBlocks.add((T) gifBlock);
            }
        }
        return filteredBlocks;
    }

    private List<GifImageData> findAllImageData(final GifImageContents imageContents) throws ImagingException {
        final List<ImageDescriptor> descriptors = findAllBlocks(imageContents.blocks, IMAGE_SEPARATOR);

        if (descriptors.isEmpty()) {
            throw new ImagingException("GIF: Couldn't read Image Descriptor");
        }

        final List<GraphicControlExtension> gcExtensions = findAllBlocks(imageContents.blocks, GRAPHIC_CONTROL_EXTENSION);

        if (!gcExtensions.isEmpty() && gcExtensions.size() != descriptors.size()) {
            throw new ImagingException("GIF: Invalid amount of Graphic Control Extensions");
        }

        final List<GifImageData> imageData = Allocator.arrayList(descriptors.size());
        for (int i = 0; i < descriptors.size(); i++) {
            final ImageDescriptor descriptor = descriptors.get(i);
            if (descriptor == null) {
                throw new ImagingException(String.format("GIF: Couldn't read Image Descriptor of image number %d", i));
            }

            final GraphicControlExtension gce = gcExtensions.isEmpty() ? null : gcExtensions.get(i);

            imageData.add(new GifImageData(descriptor, gce));
        }

        return imageData;
    }

    private GifBlock findBlock(final List<GifBlock> blocks, final int code) {
        for (final GifBlock gifBlock : blocks) {
            if (gifBlock.blockCode == code) {
                return gifBlock;
            }
        }
        return null;
    }

    private GifImageData findFirstImageData(final GifImageContents imageContents) throws ImagingException {
        final ImageDescriptor descriptor = (ImageDescriptor) findBlock(imageContents.blocks, IMAGE_SEPARATOR);

        if (descriptor == null) {
            throw new ImagingException("GIF: Couldn't read Image Descriptor");
        }

        final GraphicControlExtension gce = (GraphicControlExtension) findBlock(imageContents.blocks, GRAPHIC_CONTROL_EXTENSION);

        return new GifImageData(descriptor, gce);
    }

    @Override
    protected String[] getAcceptedExtensions() {
        return ACCEPTED_EXTENSIONS;
    }

    @Override
    protected ImageFormat[] getAcceptedTypes() {
        return new ImageFormat[] { ImageFormats.GIF, //
        };
    }

    @Override
    public List<BufferedImage> getAllBufferedImages(final ByteSource byteSource) throws ImagingException, IOException {
        final GifImageContents imageContents = readFile(byteSource, false);

        final GifHeaderInfo ghi = imageContents.gifHeaderInfo;
        if (ghi == null) {
            throw new ImagingException("GIF: Couldn't read Header");
        }

        final List<GifImageData> imageData = findAllImageData(imageContents);
        final List<BufferedImage> result = Allocator.arrayList(imageData.size());
        for (final GifImageData id : imageData) {
            result.add(getBufferedImage(id, imageContents.globalColorTable));
        }
        return result;
    }

    @Override
    public BufferedImage getBufferedImage(final ByteSource byteSource, final GifImagingParameters params) throws ImagingException, IOException {
        final GifImageContents imageContents = readFile(byteSource, false);

        final GifHeaderInfo ghi = imageContents.gifHeaderInfo;
        if (ghi == null) {
            throw new ImagingException("GIF: Couldn't read Header");
        }

        final GifImageData imageData = findFirstImageData(imageContents);

        return getBufferedImage(imageData, imageContents.globalColorTable);
    }

    private BufferedImage getBufferedImage(final GifImageData imageData, final byte[] globalColorTable)
            throws ImagingException {
        final ImageDescriptor id = imageData.descriptor;
        final GraphicControlExtension gce = imageData.gce;

        final int width = id.imageWidth;
        final int height = id.imageHeight;

        boolean hasAlpha = false;
        if (gce != null && gce.transparency) {
            hasAlpha = true;
        }

        final ImageBuilder imageBuilder = new ImageBuilder(width, height, hasAlpha);

        final int[] colorTable;
        if (id.localColorTable != null) {
            colorTable = getColorTable(id.localColorTable);
        } else if (globalColorTable != null) {
            colorTable = getColorTable(globalColorTable);
        } else {
            throw new ImagingException("Gif: No Color Table");
        }

        int transparentIndex = -1;
        if (gce != null && hasAlpha) {
            transparentIndex = gce.transparentColorIndex;
        }

        int counter = 0;

        final int rowsInPass1 = (height + 7) / 8;
        final int rowsInPass2 = (height + 3) / 8;
        final int rowsInPass3 = (height + 1) / 4;
        final int rowsInPass4 = height / 2;

        for (int row = 0; row < height; row++) {
            final int y;
            if (id.interlaceFlag) {
                int theRow = row;
                if (theRow < rowsInPass1) {
                    y = theRow * 8;
                } else {
                    theRow -= rowsInPass1;
                    if (theRow < rowsInPass2) {
                        y = 4 + theRow * 8;
                    } else {
                        theRow -= rowsInPass2;
                        if (theRow < rowsInPass3) {
                            y = 2 + theRow * 4;
                        } else {
                            theRow -= rowsInPass3;
                            if (theRow >= rowsInPass4) {
                                throw new ImagingException("Gif: Strange Row");
                            }
                            y = 1 + theRow * 2;
                        }
                    }
                }
            } else {
                y = row;
            }

            for (int x = 0; x < width; x++) {
                if (counter >= id.imageData.length) {
                    throw new ImagingException(
                            String.format("Invalid GIF image data length [%d], greater than the image data length [%d]", id.imageData.length, width));
                }
                final int index = 0xff & id.imageData[counter++];
                if (index >= colorTable.length) {
                    throw new ImagingException(
                            String.format("Invalid GIF color table index [%d], greater than the color table length [%d]", index, colorTable.length));
                }
                int rgb = colorTable[index];

                if (transparentIndex == index) {
                    rgb = 0x00;
                }
                imageBuilder.setRgb(x, y, rgb);
            }
        }

        return imageBuilder.getBufferedImage();
    }

    private int[] getColorTable(final byte[] bytes) throws ImagingException {
        if (bytes.length % 3 != 0) {
            throw new ImagingException("Bad Color Table Length: " + bytes.length);
        }
        final int length = bytes.length / 3;

        final int[] result = Allocator.intArray(length);

        for (int i = 0; i < length; i++) {
            final int red = 0xff & bytes[i * 3 + 0];
            final int green = 0xff & bytes[i * 3 + 1];
            final int blue = 0xff & bytes[i * 3 + 2];

            final int alpha = 0xff;

            final int rgb = alpha << 24 | red << 16 | green << 8 | blue << 0;
            result[i] = rgb;
        }

        return result;
    }

    private List<String> getComments(final List<GifBlock> blocks) throws IOException {
        final List<String> result = new ArrayList<>();
        final int code = 0x21fe;

        for (final GifBlock block : blocks) {
            if (block.blockCode == code) {
                final byte[] bytes = ((GenericGifBlock) block).appendSubBlocks();
                result.add(new String(bytes, StandardCharsets.US_ASCII));
            }
        }

        return result;
    }

    @Override
    public String getDefaultExtension() {
        return DEFAULT_EXTENSION;
    }

    @Override
    public GifImagingParameters getDefaultParameters() {
        return new GifImagingParameters();
    }

    @Override
    public FormatCompliance getFormatCompliance(final ByteSource byteSource) throws ImagingException, IOException {
        final FormatCompliance result = new FormatCompliance(byteSource.toString());

        readFile(byteSource, false, result);

        return result;
    }

    @Override
    public byte[] getIccProfileBytes(final ByteSource byteSource, final GifImagingParameters params) throws ImagingException, IOException {
        return null;
    }

    @Override
    public ImageInfo getImageInfo(final ByteSource byteSource, final GifImagingParameters params) throws ImagingException, IOException {
        final GifImageContents blocks = readFile(byteSource, GifImagingParameters.getStopReadingBeforeImageData(params));

        final GifHeaderInfo bhi = blocks.gifHeaderInfo;
        if (bhi == null) {
            throw new ImagingException("GIF: Couldn't read Header");
        }

        final ImageDescriptor id = (ImageDescriptor) findBlock(blocks.blocks, IMAGE_SEPARATOR);
        if (id == null) {
            throw new ImagingException("GIF: Couldn't read ImageDescriptor");
        }

        final GraphicControlExtension gce = (GraphicControlExtension) findBlock(blocks.blocks, GRAPHIC_CONTROL_EXTENSION);

        final int height = bhi.logicalScreenHeight;
        final int width = bhi.logicalScreenWidth;

        final List<String> comments = getComments(blocks.blocks);
        final int bitsPerPixel = bhi.colorResolution + 1;
        final ImageFormat format = ImageFormats.GIF;
        final String formatName = "Graphics Interchange Format";
        final String mimeType = "image/gif";

        final int numberOfImages = findAllBlocks(blocks.blocks, IMAGE_SEPARATOR).size();

        final boolean progressive = id.interlaceFlag;

        final int physicalWidthDpi = 72;
        final float physicalWidthInch = (float) ((double) width / (double) physicalWidthDpi);
        final int physicalHeightDpi = 72;
        final float physicalHeightInch = (float) ((double) height / (double) physicalHeightDpi);

        final String formatDetails = "GIF " + (char) blocks.gifHeaderInfo.version1 + (char) blocks.gifHeaderInfo.version2
                + (char) blocks.gifHeaderInfo.version3;

        boolean transparent = false;
        if (gce != null && gce.transparency) {
            transparent = true;
        }

        final boolean usesPalette = true;
        final ImageInfo.ColorType colorType = ImageInfo.ColorType.RGB;
        final ImageInfo.CompressionAlgorithm compressionAlgorithm = ImageInfo.CompressionAlgorithm.LZW;

        return new ImageInfo(formatDetails, bitsPerPixel, comments, format, formatName, height, mimeType, numberOfImages, physicalHeightDpi, physicalHeightInch,
                physicalWidthDpi, physicalWidthInch, width, progressive, transparent, usesPalette, colorType, compressionAlgorithm);
    }

    @Override
    public Dimension getImageSize(final ByteSource byteSource, final GifImagingParameters params) throws ImagingException, IOException {
        final GifImageContents blocks = readFile(byteSource, false);

        final GifHeaderInfo bhi = blocks.gifHeaderInfo;
        if (bhi == null) {
            throw new ImagingException("GIF: Couldn't read Header");
        }

        // The logical screen width and height defines the overall dimensions of the image
        // space from the top left corner. This does not necessarily match the dimensions
        // of any individual image, or even the dimensions created by overlapping all
        // images (since each images might have an offset from the top left corner).
        // Nevertheless, these fields indicate the desired screen dimensions when rendering the GIF.
        return new Dimension(bhi.logicalScreenWidth, bhi.logicalScreenHeight);
    }

    @Override
    public ImageMetadata getMetadata(final ByteSource byteSource, final GifImagingParameters params) throws ImagingException, IOException {
        final GifImageContents imageContents = readFile(byteSource, GifImagingParameters.getStopReadingBeforeImageData(params));

        final GifHeaderInfo bhi = imageContents.gifHeaderInfo;
        if (bhi == null) {
            throw new ImagingException("GIF: Couldn't read Header");
        }

        final List<GifImageData> imageData = findAllImageData(imageContents);
        final List<GifImageMetadataItem> metadataItems = Allocator.arrayList(imageData.size());
        for (final GifImageData id : imageData) {
            final DisposalMethod disposalMethod = createDisposalMethodFromIntValue(id.gce.dispose);
            metadataItems.add(new GifImageMetadataItem(id.gce.delay, id.descriptor.imageLeftPosition, id.descriptor.imageTopPosition, disposalMethod));
        }
        return new GifImageMetadata(bhi.logicalScreenWidth, bhi.logicalScreenHeight, metadataItems);
    }

    @Override
    public String getName() {
        return "Graphics Interchange Format";
    }

    /**
     * Extracts embedded XML metadata as XML string.
     * <p>
     *
     * @param byteSource File containing image data.
     * @param params     Map of optional parameters, defined in ImagingConstants.
     * @return Xmp Xml as String, if present. Otherwise, returns null.
     */
    @Override
    public String getXmpXml(final ByteSource byteSource, final XmpImagingParameters<GifImagingParameters> params) throws ImagingException, IOException {
        try (InputStream is = byteSource.getInputStream()) {
            final GifHeaderInfo ghi = readHeader(is, null);

            if (ghi.globalColorTableFlag) {
                readColorTable(is, ghi.sizeOfGlobalColorTable);
            }

            final List<GifBlock> blocks = readBlocks(ghi, is, true, null);

            final List<String> result = new ArrayList<>();
            for (final GifBlock block : blocks) {
                if (block.blockCode != XMP_COMPLETE_CODE) {
                    continue;
                }

                final GenericGifBlock genericBlock = (GenericGifBlock) block;

                final byte[] blockBytes = genericBlock.appendSubBlocks(true);
                if (blockBytes.length < XMP_APPLICATION_ID_AND_AUTH_CODE.length) {
                    continue;
                }

                if (!BinaryFunctions.compareBytes(blockBytes, 0, XMP_APPLICATION_ID_AND_AUTH_CODE, 0, XMP_APPLICATION_ID_AND_AUTH_CODE.length)) {
                    continue;
                }

                final byte[] gifMagicTrailer = new byte[256];
                for (int magic = 0; magic <= 0xff; magic++) {
                    gifMagicTrailer[magic] = (byte) (0xff - magic);
                }

                if (blockBytes.length < XMP_APPLICATION_ID_AND_AUTH_CODE.length + gifMagicTrailer.length) {
                    continue;
                }
                if (!BinaryFunctions.compareBytes(blockBytes, blockBytes.length - gifMagicTrailer.length, gifMagicTrailer, 0, gifMagicTrailer.length)) {
                    throw new ImagingException("XMP block in GIF missing magic trailer.");
                }

                // XMP is UTF-8 encoded xml.
                final String xml = new String(blockBytes, XMP_APPLICATION_ID_AND_AUTH_CODE.length,
                        blockBytes.length - (XMP_APPLICATION_ID_AND_AUTH_CODE.length + gifMagicTrailer.length), StandardCharsets.UTF_8);
                result.add(xml);
            }

            if (result.isEmpty()) {
                return null;
            }
            if (result.size() > 1) {
                throw new ImagingException("More than one XMP Block in GIF.");
            }
            return result.get(0);
        }
    }

    private List<GifBlock> readBlocks(final GifHeaderInfo ghi, final InputStream is, final boolean stopBeforeImageData, final FormatCompliance formatCompliance)
            throws ImagingException, IOException {
        final List<GifBlock> result = new ArrayList<>();

        while (true) {
            final int code = is.read();

            switch (code) {
            case -1:
                throw new ImagingException("GIF: unexpected end of data");

            case IMAGE_SEPARATOR:
                final ImageDescriptor id = readImageDescriptor(ghi, code, is, stopBeforeImageData, formatCompliance);
                result.add(id);
                // if (stopBeforeImageData)
                // return result;

                break;

            case EXTENSION_CODE: {
                final int extensionCode = is.read();
                final int completeCode = (0xff & code) << 8 | 0xff & extensionCode;

                switch (extensionCode) {
                case 0xf9:
                    final GraphicControlExtension gce = readGraphicControlExtension(completeCode, is);
                    result.add(gce);
                    break;

                case COMMENT_EXTENSION:
                case PLAIN_TEXT_EXTENSION: {
                    final GenericGifBlock block = readGenericGifBlock(is, completeCode);
                    result.add(block);
                    break;
                }

                case APPLICATION_EXTENSION_LABEL: {
                    // 255 (hex 0xFF) Application
                    // Extension Label
                    final byte[] label = readSubBlock(is);

                    if (formatCompliance != null) {
                        formatCompliance.addComment("Unknown Application Extension (" + new String(label, StandardCharsets.US_ASCII) + ")", completeCode);
                    }

                    if (label.length > 0) {
                        final GenericGifBlock block = readGenericGifBlock(is, completeCode, label);
                        result.add(block);
                    }
                    break;
                }

                default: {

                    if (formatCompliance != null) {
                        formatCompliance.addComment("Unknown block", completeCode);
                    }

                    final GenericGifBlock block = readGenericGifBlock(is, completeCode);
                    result.add(block);
                    break;
                }
                }
            }
                break;

            case TERMINATOR_BYTE:
                return result;

            case 0x00: // bad byte, but keep going and see what happens
                break;

            default:
                throw new ImagingException("GIF: unknown code: " + code);
            }
        }
    }

    private byte[] readColorTable(final InputStream is, final int tableSize) throws IOException {
        final int actualSize = convertColorTableSize(tableSize);

        return BinaryFunctions.readBytes("block", is, actualSize, "GIF: corrupt Color Table");
    }

    private GifImageContents readFile(final ByteSource byteSource, final boolean stopBeforeImageData) throws ImagingException, IOException {
        return readFile(byteSource, stopBeforeImageData, FormatCompliance.getDefault());
    }

    private GifImageContents readFile(final ByteSource byteSource, final boolean stopBeforeImageData, final FormatCompliance formatCompliance)
            throws ImagingException, IOException {
        try (InputStream is = byteSource.getInputStream()) {
            final GifHeaderInfo ghi = readHeader(is, formatCompliance);

            byte[] globalColorTable = null;
            if (ghi.globalColorTableFlag) {
                globalColorTable = readColorTable(is, ghi.sizeOfGlobalColorTable);
            }

            final List<GifBlock> blocks = readBlocks(ghi, is, stopBeforeImageData, formatCompliance);

            return new GifImageContents(ghi, globalColorTable, blocks);
        }
    }

    private GenericGifBlock readGenericGifBlock(final InputStream is, final int code) throws IOException {
        return readGenericGifBlock(is, code, null);
    }

    private GenericGifBlock readGenericGifBlock(final InputStream is, final int code, final byte[] first) throws IOException {
        final List<byte[]> subBlocks = new ArrayList<>();

        if (first != null) {
            subBlocks.add(first);
        }

        while (true) {
            final byte[] bytes = readSubBlock(is);
            if (bytes.length < 1) {
                break;
            }
            subBlocks.add(bytes);
        }

        return new GenericGifBlock(code, subBlocks);
    }

    private GraphicControlExtension readGraphicControlExtension(final int code, final InputStream is) throws IOException {
        BinaryFunctions.readByte("block_size", is, "GIF: corrupt GraphicControlExt");
        final int packed = BinaryFunctions.readByte("packed fields", is, "GIF: corrupt GraphicControlExt");

        final int dispose = (packed & 0x1c) >> 2; // disposal method
        final boolean transparency = (packed & 1) != 0;

        final int delay = BinaryFunctions.read2Bytes("delay in milliseconds", is, "GIF: corrupt GraphicControlExt", getByteOrder());
        final int transparentColorIndex = 0xff & BinaryFunctions.readByte("transparent color index", is, "GIF: corrupt GraphicControlExt");
        BinaryFunctions.readByte("block terminator", is, "GIF: corrupt GraphicControlExt");

        return new GraphicControlExtension(code, packed, dispose, transparency, delay, transparentColorIndex);
    }

    private GifHeaderInfo readHeader(final InputStream is, final FormatCompliance formatCompliance) throws ImagingException, IOException {
        final byte identifier1 = BinaryFunctions.readByte("identifier1", is, "Not a Valid GIF File");
        final byte identifier2 = BinaryFunctions.readByte("identifier2", is, "Not a Valid GIF File");
        final byte identifier3 = BinaryFunctions.readByte("identifier3", is, "Not a Valid GIF File");

        final byte version1 = BinaryFunctions.readByte("version1", is, "Not a Valid GIF File");
        final byte version2 = BinaryFunctions.readByte("version2", is, "Not a Valid GIF File");
        final byte version3 = BinaryFunctions.readByte("version3", is, "Not a Valid GIF File");

        if (formatCompliance != null) {
            formatCompliance.compareBytes("Signature", GIF_HEADER_SIGNATURE, new byte[] { identifier1, identifier2, identifier3 });
            formatCompliance.compare("version", 56, version1);
            formatCompliance.compare("version", new int[] { 55, 57, }, version2);
            formatCompliance.compare("version", 97, version3);
        }

        if (LOGGER.isLoggable(Level.FINEST)) {
            BinaryFunctions.logCharQuad("identifier: ", identifier1 << 16 | identifier2 << 8 | identifier3 << 0);
            BinaryFunctions.logCharQuad("version: ", version1 << 16 | version2 << 8 | version3 << 0);
        }

        final int logicalScreenWidth = BinaryFunctions.read2Bytes("Logical Screen Width", is, "Not a Valid GIF File", getByteOrder());
        final int logicalScreenHeight = BinaryFunctions.read2Bytes("Logical Screen Height", is, "Not a Valid GIF File", getByteOrder());

        if (formatCompliance != null) {
            formatCompliance.checkBounds("Width", 1, Integer.MAX_VALUE, logicalScreenWidth);
            formatCompliance.checkBounds("Height", 1, Integer.MAX_VALUE, logicalScreenHeight);
        }

        final byte packedFields = BinaryFunctions.readByte("Packed Fields", is, "Not a Valid GIF File");
        final byte backgroundColorIndex = BinaryFunctions.readByte("Background Color Index", is, "Not a Valid GIF File");
        final byte pixelAspectRatio = BinaryFunctions.readByte("Pixel Aspect Ratio", is, "Not a Valid GIF File");

        if (LOGGER.isLoggable(Level.FINEST)) {
            BinaryFunctions.logByteBits("PackedFields bits", packedFields);
        }

        final boolean globalColorTableFlag = (packedFields & 128) > 0;
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("GlobalColorTableFlag: " + globalColorTableFlag);
        }
        final byte colorResolution = (byte) (packedFields >> 4 & 7);
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("ColorResolution: " + colorResolution);
        }
        final boolean sortFlag = (packedFields & 8) > 0;
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("SortFlag: " + sortFlag);
        }
        final byte sizeofGlobalColorTable = (byte) (packedFields & 7);
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("SizeofGlobalColorTable: " + sizeofGlobalColorTable);
        }

        if (formatCompliance != null && globalColorTableFlag && backgroundColorIndex != -1) {
            formatCompliance.checkBounds("Background Color Index", 0, convertColorTableSize(sizeofGlobalColorTable), backgroundColorIndex);
        }

        return new GifHeaderInfo(identifier1, identifier2, identifier3, version1, version2, version3, logicalScreenWidth, logicalScreenHeight, packedFields,
                backgroundColorIndex, pixelAspectRatio, globalColorTableFlag, colorResolution, sortFlag, sizeofGlobalColorTable);
    }

    private ImageDescriptor readImageDescriptor(final GifHeaderInfo ghi, final int blockCode, final InputStream is, final boolean stopBeforeImageData,
            final FormatCompliance formatCompliance) throws ImagingException, IOException {
        final int imageLeftPosition = BinaryFunctions.read2Bytes("Image Left Position", is, "Not a Valid GIF File", getByteOrder());
        final int imageTopPosition = BinaryFunctions.read2Bytes("Image Top Position", is, "Not a Valid GIF File", getByteOrder());
        final int imageWidth = BinaryFunctions.read2Bytes("Image Width", is, "Not a Valid GIF File", getByteOrder());
        final int imageHeight = BinaryFunctions.read2Bytes("Image Height", is, "Not a Valid GIF File", getByteOrder());
        final byte packedFields = BinaryFunctions.readByte("Packed Fields", is, "Not a Valid GIF File");

        if (formatCompliance != null) {
            formatCompliance.checkBounds("Width", 1, ghi.logicalScreenWidth, imageWidth);
            formatCompliance.checkBounds("Height", 1, ghi.logicalScreenHeight, imageHeight);
            formatCompliance.checkBounds("Left Position", 0, ghi.logicalScreenWidth - imageWidth, imageLeftPosition);
            formatCompliance.checkBounds("Top Position", 0, ghi.logicalScreenHeight - imageHeight, imageTopPosition);
        }

        if (LOGGER.isLoggable(Level.FINEST)) {
            BinaryFunctions.logByteBits("PackedFields bits", packedFields);
        }

        final boolean localColorTableFlag = (packedFields >> 7 & 1) > 0;
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("LocalColorTableFlag: " + localColorTableFlag);
        }
        final boolean interlaceFlag = (packedFields >> 6 & 1) > 0;
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Interlace Flag: " + interlaceFlag);
        }
        final boolean sortFlag = (packedFields >> 5 & 1) > 0;
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Sort Flag: " + sortFlag);
        }

        final byte sizeOfLocalColorTable = (byte) (packedFields & 7);
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("SizeofLocalColorTable: " + sizeOfLocalColorTable);
        }

        byte[] localColorTable = null;
        if (localColorTableFlag) {
            localColorTable = readColorTable(is, sizeOfLocalColorTable);
        }

        byte[] imageData = null;
        if (!stopBeforeImageData) {
            final int lzwMinimumCodeSize = is.read();

            final GenericGifBlock block = readGenericGifBlock(is, -1);
            final byte[] bytes = block.appendSubBlocks();
            final InputStream bais = new ByteArrayInputStream(bytes);

            final int size = imageWidth * imageHeight;
            final MyLzwDecompressor myLzwDecompressor = new MyLzwDecompressor(lzwMinimumCodeSize, ByteOrder.LITTLE_ENDIAN, false);
            imageData = myLzwDecompressor.decompress(bais, size);
        } else {
            final int LZWMinimumCodeSize = is.read();
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("LZWMinimumCodeSize: " + LZWMinimumCodeSize);
            }

            readGenericGifBlock(is, -1);
        }

        return new ImageDescriptor(blockCode, imageLeftPosition, imageTopPosition, imageWidth, imageHeight, packedFields, localColorTableFlag, interlaceFlag,
                sortFlag, sizeOfLocalColorTable, localColorTable, imageData);
    }

    private byte[] readSubBlock(final InputStream is) throws IOException {
        final int blockSize = 0xff & BinaryFunctions.readByte("blockSize", is, "GIF: corrupt block");

        return BinaryFunctions.readBytes("block", is, blockSize, "GIF: corrupt block");
    }

    private int simplePow(final int base, final int power) {
        int result = 1;

        for (int i = 0; i < power; i++) {
            result *= base;
        }

        return result;
    }

    private void writeAsSubBlocks(final byte[] bytes, final OutputStream os) throws IOException {
        int index = 0;

        while (index < bytes.length) {
            final int blockSize = Math.min(bytes.length - index, 255);
            os.write(blockSize);
            os.write(bytes, index, blockSize);
            index += blockSize;
        }
        os.write(0); // last block
    }

    @Override
    public void writeImage(final BufferedImage src, final OutputStream os, GifImagingParameters params) throws ImagingException, IOException {
        if (params == null) {
            params = new GifImagingParameters();
        }

        final String xmpXml = params.getXmpXml();

        final int width = src.getWidth();
        final int height = src.getHeight();

        final boolean hasAlpha = new PaletteFactory().hasTransparency(src);

        final int maxColors = hasAlpha ? 255 : 256;

        Palette palette2 = new PaletteFactory().makeExactRgbPaletteSimple(src, maxColors);
        // int[] palette = new PaletteFactory().makePaletteSimple(src, 256);
        // Map palette_map = paletteToMap(palette);

        if (palette2 == null) {
            palette2 = new PaletteFactory().makeQuantizedRgbPalette(src, maxColors);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("quantizing");
            }
        } else if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("exact palette");
        }

        if (palette2 == null) {
            throw new ImagingException("Gif: can't write images with more than 256 colors");
        }
        final int paletteSize = palette2.length() + (hasAlpha ? 1 : 0);

        try (AbstractBinaryOutputStream bos = AbstractBinaryOutputStream.littleEndian(os)) {

            // write Header
            os.write(0x47); // G magic numbers
            os.write(0x49); // I
            os.write(0x46); // F

            os.write(0x38); // 8 version magic numbers
            os.write(0x39); // 9
            os.write(0x61); // a

            // Logical Screen Descriptor.

            bos.write2Bytes(width);
            bos.write2Bytes(height);

            final int colorTableScaleLessOne = paletteSize > 128 ? 7
                    : paletteSize > 64 ? 6 : paletteSize > 32 ? 5 : paletteSize > 16 ? 4 : paletteSize > 8 ? 3 : paletteSize > 4 ? 2 : paletteSize > 2 ? 1 : 0;

            final int colorTableSizeInFormat = 1 << colorTableScaleLessOne + 1;
            {
                final byte colorResolution = (byte) colorTableScaleLessOne; // TODO:
                final int packedFields = (7 & colorResolution) * 16;
                bos.write(packedFields); // one byte
            }
            {
                final byte backgroundColorIndex = 0;
                bos.write(backgroundColorIndex);
            }
            {
                final byte pixelAspectRatio = 0;
                bos.write(pixelAspectRatio);
            }

            // {
            // write Global Color Table.

            // }

            { // ALWAYS write GraphicControlExtension
                bos.write(EXTENSION_CODE);
                bos.write((byte) 0xf9);
                // bos.write(0xff & (kGraphicControlExtension >> 8));
                // bos.write(0xff & (kGraphicControlExtension >> 0));

                bos.write((byte) 4); // block size;
                final int packedFields = hasAlpha ? 1 : 0; // transparency flag
                bos.write((byte) packedFields);
                bos.write((byte) 0); // Delay Time
                bos.write((byte) 0); // Delay Time
                bos.write((byte) (hasAlpha ? palette2.length() : 0)); // Transparent
                // Color
                // Index
                bos.write((byte) 0); // terminator
            }

            if (null != xmpXml) {
                bos.write(EXTENSION_CODE);
                bos.write(APPLICATION_EXTENSION_LABEL);

                bos.write(XMP_APPLICATION_ID_AND_AUTH_CODE.length); // 0x0B
                bos.write(XMP_APPLICATION_ID_AND_AUTH_CODE);

                final byte[] xmpXmlBytes = xmpXml.getBytes(StandardCharsets.UTF_8);
                bos.write(xmpXmlBytes);

                // write "magic trailer"
                for (int magic = 0; magic <= 0xff; magic++) {
                    bos.write(0xff - magic);
                }

                bos.write((byte) 0); // terminator

            }

            { // Image Descriptor.
                bos.write(IMAGE_SEPARATOR);
                bos.write2Bytes(0); // Image Left Position
                bos.write2Bytes(0); // Image Top Position
                bos.write2Bytes(width); // Image Width
                bos.write2Bytes(height); // Image Height

                {
                    final boolean localColorTableFlag = true;
                    // boolean LocalColorTableFlag = false;
                    final boolean interlaceFlag = false;
                    final boolean sortFlag = false;
                    final int sizeOfLocalColorTable = colorTableScaleLessOne;

                    // int SizeOfLocalColorTable = 0;

                    final int packedFields;
                    if (localColorTableFlag) {
                        packedFields = LOCAL_COLOR_TABLE_FLAG_MASK | (interlaceFlag ? INTERLACE_FLAG_MASK : 0) | (sortFlag ? SORT_FLAG_MASK : 0)
                                | 7 & sizeOfLocalColorTable;
                    } else {
                        packedFields = 0 | (interlaceFlag ? INTERLACE_FLAG_MASK : 0) | (sortFlag ? SORT_FLAG_MASK : 0) | 7 & sizeOfLocalColorTable;
                    }
                    bos.write(packedFields); // one byte
                }
            }

            { // write Local Color Table.
                for (int i = 0; i < colorTableSizeInFormat; i++) {
                    if (i < palette2.length()) {
                        final int rgb = palette2.getEntry(i);

                        final int red = 0xff & rgb >> 16;
                        final int green = 0xff & rgb >> 8;
                        final int blue = 0xff & rgb >> 0;

                        bos.write(red);
                        bos.write(green);
                        bos.write(blue);
                    } else {
                        bos.write(0);
                        bos.write(0);
                        bos.write(0);
                    }
                }
            }

            { // get Image Data.
//            int image_data_total = 0;

                int lzwMinimumCodeSize = colorTableScaleLessOne + 1;
                // LZWMinimumCodeSize = Math.max(8, LZWMinimumCodeSize);
                if (lzwMinimumCodeSize < 2) {
                    lzwMinimumCodeSize = 2;
                }

                // TODO:
                // make
                // better
                // choice
                // here.
                bos.write(lzwMinimumCodeSize);

                final MyLzwCompressor compressor = new MyLzwCompressor(lzwMinimumCodeSize, ByteOrder.LITTLE_ENDIAN, false); // GIF
                // Mode);

                final byte[] imageData = Allocator.byteArray(width * height);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        final int argb = src.getRGB(x, y);
                        final int rgb = 0xffffff & argb;
                        final int index;

                        if (hasAlpha) {
                            final int alpha = 0xff & argb >> 24;
                            final int alphaThreshold = 255;
                            if (alpha < alphaThreshold) {
                                index = palette2.length(); // is transparent
                            } else {
                                index = palette2.getPaletteIndex(rgb);
                            }
                        } else {
                            index = palette2.getPaletteIndex(rgb);
                        }

                        imageData[y * width + x] = (byte) index;
                    }
                }

                final byte[] compressed = compressor.compress(imageData);
                writeAsSubBlocks(compressed, bos);
//            image_data_total += compressed.length;
            }

            // palette2.dump();

            bos.write(TERMINATOR_BYTE);

        }
        os.close();
    }
}
