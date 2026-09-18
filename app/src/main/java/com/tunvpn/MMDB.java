/*
 ============================================================================
 Name        : MMDB.java
 Description : Minimal, dependency-free reader for the MaxMind DB (mmdb)
               binary format, enough to resolve an IP address to its data
               record (a Map). Used by GeoIp to look up a proxy server's
               country from a bundled GeoLite2-Country.mmdb, fully offline.
 ============================================================================
*/

package com.tunvpn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MMDB {
	/* Metadata marker: 3 bytes 0xAB 0xCD 0xEF followed by "MaxMind.com". */
	private static final byte[] MAGIC = {
		(byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
		'M', 'a', 'x', 'M', 'i', 'n', 'd', '.', 'c', 'o', 'm'
	};

	private final byte[] data;
	private final int nodeCount;
	private final int recordSize;   /* bits per record (24/28/32) */
	private final int ipVersion;    /* 4 or 6 */
	private final int searchTreeSize;   /* bytes */
	private final int dataSectionStart; /* = searchTreeSize + 16 */

	public static MMDB open(byte[] database) throws IOException {
		return new MMDB(database);
	}

	private MMDB(byte[] database) throws IOException {
		this.data = database;
		int idx = lastIndexOf(MAGIC);
		if (idx < 0)
		  throw new IOException("not a MaxMind DB (marker not found)");
		Object meta = decodeField(new int[] { idx + MAGIC.length }, 0, 0);
		if (!(meta instanceof Map))
		  throw new IOException("invalid MaxMind DB metadata");
		@SuppressWarnings("unchecked")
		Map<Object, Object> m = (Map<Object, Object>) meta;
		this.nodeCount = (int) asLong(m.get("node_count"));
		this.recordSize = (int) asLong(m.get("record_size"));
		this.ipVersion = (int) asLong(m.get("ip_version"));
		if (nodeCount <= 0 || recordSize <= 0)
		  throw new IOException("invalid MaxMind DB metadata values");
		long sts = (long) nodeCount * recordSize / 4;
		this.searchTreeSize = (int) sts;
		this.dataSectionStart = (int) sts + 16;
	}

	/* Resolve an IP to its record (a Map), or null if not found / unsupported. */
	public Object get(InetAddress addr) {
		if (addr == null)
		  return null;
		byte[] a = addr.getAddress();
		byte[] bits;
		if (ipVersion == 4) {
			if (a.length == 4)
			  bits = a;
			else if (a.length == 16 && a[10] == (byte) 0xFF && a[11] == (byte) 0xFF)
			  bits = Arrays.copyOfRange(a, 12, 16);
			else
			  return null;
		} else { /* IPv6 / unified database */
			if (a.length == 16)
			  bits = a;
			else if (a.length == 4) { /* map IPv4 into ::ffff:0:0/96 */
				bits = new byte[16];
				bits[10] = (byte) 0xFF;
				bits[11] = (byte) 0xFF;
				System.arraycopy(a, 0, bits, 12, 4);
			} else
			  return null;
		}
		int bitLen = (ipVersion == 4) ? 32 : 128;
		int node = 0;
		for (int i = 0; i < bitLen; i++) {
			int bit = (bits[i >>> 3] >> (7 - (i & 7))) & 1;
			long rec = readRecord(node, bit);
			if (rec == nodeCount)
			  return null;
			if (rec < nodeCount) {
				node = (int) rec;
				continue;
			}
			/* Data records resolve against the start of the data section, which is
			   searchTreeSize + 16 (the same base used for pointers inside
			   decodeField). Using searchTreeSize here instead would read every
			   record 16 bytes too early and decode the wrong country. */
			long off = (long) dataSectionStart + (rec - nodeCount);
			return decodeField(new int[] { (int) off }, dataSectionStart, 0);
		}
		return null;
	}

	/* --- search tree --- */

	private long readRecord(int node, int bit) {
		int baseBit = node * 2 * recordSize + (bit == 0 ? 0 : recordSize);
		return readBits(baseBit, recordSize);
	}

	private long readBits(int bitOffset, int bitLength) {
		long result = 0;
		for (int i = 0; i < bitLength; i++) {
			int idx = bitOffset + i;
			int b = (data[idx >>> 3] >> (7 - (idx & 7))) & 1;
			result = (result << 1) | b;
		}
		return result;
	}

	/* --- data section decoder --- */

	private Object decodeField(int[] p, int dataSectionStart, int depth) {
		if (depth > 256)
		  return null;
		int pos = p[0];
		int ctrl = data[pos++] & 0xFF;
		int type = ctrl >> 5;
		int size = ctrl & 0x1F;
		if (type == 0) { /* extended type */
			type = (data[pos++] & 0xFF) + 7;
		}
		int ss = -1, vvv = -1;
		long length = size;
		if (type == 1) { /* POINTER: low 5 bits are SS(2) + VVV(3) */
			ss = (size >> 3) & 0x3;
			vvv = size & 0x7;
		} else {
			if (size == 29)
			  length = 29 + (data[pos++] & 0xFF);
			else if (size == 30)
			  length = 285 + (((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF));
			else if (size == 31)
			  length = 65821 + (((data[pos++] & 0xFF) << 16)
					| ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF));
		}

		switch (type) {
		case 1: { /* POINTER */
			long pointer;
			if (ss == 0)
			  pointer = ((long) vvv << 8) | (data[pos++] & 0xFF);
			else if (ss == 1)
			  pointer = (((long) vvv << 16)
					| (((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF))) + 2048;
			else if (ss == 2)
			  pointer = (((long) vvv << 24)
					| (((data[pos++] & 0xFF) << 16)
					   | ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF))) + 526336;
			else
			  pointer = ((long) (data[pos++] & 0xFF) << 24)
					| ((data[pos++] & 0xFF) << 16)
					| ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
			p[0] = pos;
			return decodeField(new int[] { dataSectionStart + (int) pointer },
					dataSectionStart, depth + 1);
		}
		case 2: { /* UTF-8 string */
			String s = new String(data, pos, (int) length, StandardCharsets.UTF_8);
			pos += length;
			p[0] = pos;
			return s;
		}
		case 3: { /* double (8 bytes) */
			double d = Double.longBitsToDouble(readLongBE(pos, 8));
			pos += 8;
			p[0] = pos;
			return d;
		}
		case 4: { /* bytes */
			byte[] b = Arrays.copyOfRange(data, pos, pos + (int) length);
			pos += length;
			p[0] = pos;
			return b;
		}
		case 5: /* uint16 */
		case 6: /* uint32 */
		case 9: { /* uint64 */
			long v = readUInt(pos, (int) length);
			pos += length;
			p[0] = pos;
			return v;
		}
		case 8: { /* int32 (signed) */
			long v = readInt(pos, (int) length);
			pos += length;
			p[0] = pos;
			return v;
		}
		case 7: { /* map */
			Map<String, Object> map = new LinkedHashMap<String, Object>();
			for (long k = 0; k < length; k++) {
				Object key = decodeField(p, dataSectionStart, depth + 1);
				Object val = decodeField(p, dataSectionStart, depth + 1);
				if (key instanceof String)
				  map.put((String) key, val);
			}
			return map;
		}
		case 11: { /* array */
			List<Object> list = new ArrayList<Object>();
			for (long k = 0; k < length; k++)
				list.add(decodeField(p, dataSectionStart, depth + 1));
			return list;
		}
		case 14: /* boolean */
			p[0] = pos;
			return (size == 1);
		case 15: { /* float (4 bytes) */
			float f = Float.intBitsToFloat((int) readLongBE(pos, 4));
			pos += 4;
			p[0] = pos;
			return f;
		}
		case 10: /* uint128: not needed here */
		default:
			pos += length;
			p[0] = pos;
			return null;
		}
	}

	private long readUInt(int pos, int n) {
		long v = 0;
		for (int i = 0; i < n; i++)
			v = (v << 8) | (data[pos + i] & 0xFF);
		return v;
	}

	private long readInt(int pos, int n) {
		long v = readUInt(pos, n);
		int bits = n * 8;
		if (bits < 64 && bits > 0) {
			long signBit = 1L << (bits - 1);
			if ((v & signBit) != 0)
			  v -= (1L << bits);
		}
		return v;
	}

	private long readLongBE(int pos, int n) {
		long v = 0;
		for (int i = 0; i < n; i++)
			v = (v << 8) | (data[pos + i] & 0xFF);
		return v;
	}

	private static long asLong(Object o) {
		if (o instanceof Long)
		  return (Long) o;
		if (o instanceof Integer)
		  return (Integer) o;
		return 0;
	}

	private int lastIndexOf(byte[] marker) {
		int n = data.length - marker.length;
		for (int i = n; i >= 0; i--) {
			boolean ok = true;
			for (int j = 0; j < marker.length; j++) {
				if (data[i + j] != marker[j]) {
					ok = false;
					break;
				}
			}
			if (ok)
			  return i;
		}
		return -1;
	}

	/* Read an entire InputStream into a byte array (API-level safe). */
	public static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
			out.write(buf, 0, n);
		return out.toByteArray();
	}
}
