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

package org.apache.commons.imaging.formats.jpeg.xmp;

import java.nio.charset.StandardCharsets;

import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.BinaryFileParser;
import org.apache.commons.imaging.formats.jpeg.JpegConstants;

public class JpegXmpParser extends BinaryFileParser {

    /**
     * Constructs a new instance with the default, big-endian, byte order.
     */
    public JpegXmpParser() {
        // empty
    }

    public boolean isXmpJpegSegment(final byte[] segmentData) {
        return JpegConstants.XMP_IDENTIFIER.isStartOf(segmentData);
    }

    public String parseXmpJpegSegment(final byte[] segmentData) throws ImagingException {
        if (!isXmpJpegSegment(segmentData)) {
            throw new ImagingException("Invalid JPEG XMP Segment.");
        }
        final int index = JpegConstants.XMP_IDENTIFIER.size();

        // segment data is UTF-8 encoded xml.
        return new String(segmentData, index, segmentData.length - index, StandardCharsets.UTF_8);
    }

}
