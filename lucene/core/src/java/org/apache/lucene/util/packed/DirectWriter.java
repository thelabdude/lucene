/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util.packed;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.store.DataOutput;

/**
 * Class for writing packed integers to be directly read from Directory. Integers can be read
 * on-the-fly via {@link DirectReader}.
 *
 * <p>Unlike PackedInts, it optimizes for read i/o operations and supports &gt; 2B values. Example
 * usage:
 *
 * <pre class="prettyprint">
 *   int bitsPerValue = DirectWriter.bitsRequired(100); // values up to and including 100
 *   IndexOutput output = dir.createOutput("packed", IOContext.DEFAULT);
 *   DirectWriter writer = DirectWriter.getInstance(output, numberOfValues, bitsPerValue);
 *   for (int i = 0; i &lt; numberOfValues; i++) {
 *     writer.add(value);
 *   }
 *   writer.finish();
 *   output.close();
 * </pre>
 *
 * @see DirectReader
 */
public final class DirectWriter {
  final int bitsPerValue;
  final long numValues;
  final DataOutput output;

  long count;
  boolean finished;

  // for now, just use the existing writer under the hood
  int off;
  final byte[] nextBlocks;
  final long[] nextValues;
  final BulkOperation encoder;
  final int iterations;
  final int byteOffset;
  final int bitsUsedOffset;
  final int byteValueCount;

  DirectWriter(DataOutput output, long numValues, int bitsPerValue) {
    this.output = output;
    this.numValues = numValues;
    this.bitsPerValue = bitsPerValue;
    encoder = BulkOperation.of(PackedInts.Format.PACKED, bitsPerValue);
    this.byteOffset = 8 - bitsPerValue;
    this.bitsUsedOffset = bitsPerValue - 8;
    this.byteValueCount = encoder.byteValueCount();
    iterations =
        encoder.computeIterations(
            (int) Math.min(numValues, Integer.MAX_VALUE), PackedInts.DEFAULT_BUFFER_SIZE);
    nextBlocks = new byte[iterations * encoder.byteBlockCount()];
    nextValues = new long[iterations * encoder.byteValueCount()];
  }

  /** Adds a value to this writer */
  public void add(long l) throws IOException {
    assert bitsPerValue == 64 || (l >= 0 && l <= PackedInts.maxValue(bitsPerValue)) : bitsPerValue;
    assert !finished;
    if (count >= numValues) {
      throw new EOFException("Writing past end of stream");
    }
    nextValues[off++] = l;
    if (off == nextValues.length) {
      flush();
    }
    count++;
  }

  private void flush() throws IOException {
    if (off == 0) {
      return;
    }
    // Avoid writing bits from values that are outside of the range we need to encode
    Arrays.fill(nextValues, off, nextValues.length, 0L);
    encode(nextValues, 0, nextBlocks, 0, iterations);
    final int blockCount =
        (int) PackedInts.Format.PACKED.byteCount(PackedInts.VERSION_CURRENT, off, bitsPerValue);
    output.writeBytes(nextBlocks, blockCount);
    off = 0;
  }

  public void encode(
      long[] values, int valuesOffset, byte[] blocks, int blocksOffset, int iterations) {
    int nextBlock = 0;
    int bitsUsed = 0;
    for (int i = 0; i < byteValueCount * iterations; ++i) {
      final long v = values[valuesOffset++];
      assert PackedInts.unsignedBitsRequired(v) <= bitsPerValue;
      if (bitsUsed < byteOffset) {
        // just buffer
        nextBlock |= v << bitsUsed;
        bitsUsed += bitsPerValue;
      } else {
        // flush as many blocks as possible
        blocks[blocksOffset++] = (byte) (nextBlock | (v << bitsUsed));
        int bits = 8 - bitsUsed;
        while (bits <= bitsUsedOffset) {
          blocks[blocksOffset++] = (byte) (v >> bits);
          bits += 8;
        }
        // then buffer
        bitsUsed = bitsPerValue - bits;
        nextBlock = (int) ((v >>> bits) & ((1L << bitsUsed) - 1));
      }
    }
    assert bitsUsed == 0;
  }

  /** finishes writing */
  public void finish() throws IOException {
    if (count != numValues) {
      throw new IllegalStateException(
          "Wrong number of values added, expected: " + numValues + ", got: " + count);
    }
    assert !finished;
    flush();
    // pad for fast io: we actually only need this for certain BPV, but its just 3 bytes...
    for (int i = 0; i < 3; i++) {
      output.writeByte((byte) 0);
    }
    finished = true;
  }

  /** Returns an instance suitable for encoding {@code numValues} using {@code bitsPerValue} */
  public static DirectWriter getInstance(DataOutput output, long numValues, int bitsPerValue) {
    if (Arrays.binarySearch(SUPPORTED_BITS_PER_VALUE, bitsPerValue) < 0) {
      throw new IllegalArgumentException(
          "Unsupported bitsPerValue " + bitsPerValue + ". Did you use bitsRequired?");
    }
    return new DirectWriter(output, numValues, bitsPerValue);
  }

  /**
   * Round a number of bits per value to the next amount of bits per value that is supported by this
   * writer.
   *
   * @param bitsRequired the amount of bits required
   * @return the next number of bits per value that is gte the provided value and supported by this
   *     writer
   */
  private static int roundBits(int bitsRequired) {
    int index = Arrays.binarySearch(SUPPORTED_BITS_PER_VALUE, bitsRequired);
    if (index < 0) {
      return SUPPORTED_BITS_PER_VALUE[-index - 1];
    } else {
      return bitsRequired;
    }
  }

  /**
   * Returns how many bits are required to hold values up to and including maxValue
   *
   * @param maxValue the maximum value that should be representable.
   * @return the amount of bits needed to represent values from 0 to maxValue.
   * @see PackedInts#bitsRequired(long)
   */
  public static int bitsRequired(long maxValue) {
    return roundBits(PackedInts.bitsRequired(maxValue));
  }

  /**
   * Returns how many bits are required to hold values up to and including maxValue, interpreted as
   * an unsigned value.
   *
   * @param maxValue the maximum value that should be representable.
   * @return the amount of bits needed to represent values from 0 to maxValue.
   * @see PackedInts#unsignedBitsRequired(long)
   */
  public static int unsignedBitsRequired(long maxValue) {
    return roundBits(PackedInts.unsignedBitsRequired(maxValue));
  }

  static final int[] SUPPORTED_BITS_PER_VALUE =
      new int[] {1, 2, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64};
}
