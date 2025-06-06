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
package org.apache.commons.imaging.formats.tiff.photometricinterpreters;

import java.io.IOException;

import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.color.ColorConversions;
import org.apache.commons.imaging.common.ImageBuilder;

public class PhotometricInterpreterCmyk extends AbstractPhotometricInterpreter {
    public PhotometricInterpreterCmyk(final int samplesPerPixel, final int[] bitsPerSample, final int predictor, final int width, final int height) {
        super(samplesPerPixel, bitsPerSample, predictor, width, height);
    }

    @Override
    public void interpretPixel(final ImageBuilder imageBuilder, final int[] samples, final int x, final int y) throws ImagingException, IOException {

        final int sc = samples[0];
        final int sm = samples[1];
        final int sy = samples[2];
        final int sk = samples[3];

        final int rgb = ColorConversions.convertCmykToRgb(sc, sm, sy, sk);
        imageBuilder.setRgb(x, y, rgb);
    }

}
