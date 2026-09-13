/*
 ============================================================================
 Name        : Country.java
 Description : ISO-3166 alpha-2 country code -> display helpers for the UI:
               a flag emoji (derived from the two-letter code via regional
               indicator symbols) plus a Chinese name, with a sensible
               fallback to the raw code when the name is unknown. The special
               sentinels GLOBAL / AUTO / UNKNOWN get their own glyphs.
 ============================================================================
*/

package com.tunvpn;

import java.util.HashMap;
import java.util.Map;

public class Country {
	/* Sentinel stored in Preferences.AUTO_SELECT_COUNTRY for "fastest country
	   picked automatically from measured latency". */
	public static final String GLOBAL = "GLOBAL";
	public static final String AUTO = "AUTO";
	/* Nodes whose country could not be resolved (matches GeoIp.UNKNOWN). */
	public static final String UNKNOWN = GeoIp.UNKNOWN;

	private static final Map<String, String> CN = new HashMap<String, String>();
	static {
		CN.put(GLOBAL, "全局最快");
		CN.put(AUTO, "自动最优国家");
		CN.put(UNKNOWN, "未知地区");
		CN.put("HK", "香港");
		CN.put("TW", "台湾");
		CN.put("CN", "中国");
		CN.put("MO", "澳门");
		CN.put("JP", "日本");
		CN.put("KR", "韩国");
		CN.put("KP", "朝鲜");
		CN.put("SG", "新加坡");
		CN.put("MY", "马来西亚");
		CN.put("TH", "泰国");
		CN.put("VN", "越南");
		CN.put("PH", "菲律宾");
		CN.put("ID", "印度尼西亚");
		CN.put("IN", "印度");
		CN.put("BD", "孟加拉国");
		CN.put("PK", "巴基斯坦");
		CN.put("MM", "缅甸");
		CN.put("KH", "柬埔寨");
		CN.put("LA", "老挝");
		CN.put("MN", "蒙古");
		CN.put("US", "美国");
		CN.put("CA", "加拿大");
		CN.put("MX", "墨西哥");
		CN.put("BR", "巴西");
		CN.put("AR", "阿根廷");
		CN.put("CL", "智利");
		CN.put("CO", "哥伦比亚");
		CN.put("PE", "秘鲁");
		CN.put("GB", "英国");
		CN.put("IE", "爱尔兰");
		CN.put("FR", "法国");
		CN.put("DE", "德国");
		CN.put("NL", "荷兰");
		CN.put("BE", "比利时");
		CN.put("CH", "瑞士");
		CN.put("AT", "奥地利");
		CN.put("IT", "意大利");
		CN.put("ES", "西班牙");
		CN.put("PT", "葡萄牙");
		CN.put("SE", "瑞典");
		CN.put("NO", "挪威");
		CN.put("DK", "丹麦");
		CN.put("FI", "芬兰");
		CN.put("RU", "俄罗斯");
		CN.put("UA", "乌克兰");
		CN.put("PL", "波兰");
		CN.put("CZ", "捷克");
		CN.put("TR", "土耳其");
		CN.put("GR", "希腊");
		CN.put("RO", "罗马尼亚");
		CN.put("HU", "匈牙利");
		CN.put("BG", "保加利亚");
		CN.put("SK", "斯洛伐克");
		CN.put("SI", "斯洛文尼亚");
		CN.put("HR", "克罗地亚");
		CN.put("RS", "塞尔维亚");
		CN.put("LT", "立陶宛");
		CN.put("LV", "拉脱维亚");
		CN.put("EE", "爱沙尼亚");
		CN.put("LU", "卢森堡");
		CN.put("IS", "冰岛");
		CN.put("AU", "澳大利亚");
		CN.put("NZ", "新西兰");
		CN.put("ZA", "南非");
		CN.put("AE", "阿联酋");
		CN.put("SA", "沙特阿拉伯");
		CN.put("IL", "以色列");
		CN.put("EG", "埃及");
		CN.put("QA", "卡塔尔");
		CN.put("KW", "科威特");
		CN.put("JO", "约旦");
		CN.put("LK", "斯里兰卡");
		CN.put("NP", "尼泊尔");
		CN.put("KZ", "哈萨克斯坦");
		CN.put("UZ", "乌兹别克斯坦");
		CN.put("GE", "格鲁吉亚");
		CN.put("AM", "亚美尼亚");
		CN.put("AZ", "阿塞拜疆");
		CN.put("BY", "白俄罗斯");
		CN.put("MD", "摩尔多瓦");
	}

	/* Flag emoji for a 2-letter code (regional indicator pair). Unknown / non
	   ISO values get a neutral glyph so the name is still readable. */
	public static String flag(String cc) {
		if (cc == null)
		  return "\u2753"; /* ❓ */
		if (GLOBAL.equals(cc))
		  return "\uD83C\uDF10"; /* 🌐 */
		if (AUTO.equals(cc))
		  return "\uD83C\uDFC6"; /* 🏆 */
		if (UNKNOWN.equals(cc))
		  return "\u2753"; /* ❓ */
		if (cc.length() == 2) {
			int base = 0x1F1E6;
			int f = base + (Character.toUpperCase(cc.charAt(0)) - 'A');
			int s = base + (Character.toUpperCase(cc.charAt(1)) - 'A');
			return new String(new int[] { f, s }, 0, 2);
		}
		return "\uD83C\uDFF3"; /* 🏳 */
	}

	/* Chinese name for a code; falls back to the raw code when unknown. */
	public static String name(String cc) {
		if (cc == null || cc.isEmpty())
		  return CN.get(UNKNOWN);
		String n = CN.get(cc);
		return n != null ? n : cc;
	}

	/* "🇺🇸 美国" style label. */
	public static String display(String cc) {
		return flag(cc) + " " + name(cc);
	}

	/* Label with a trailing node count for the picker. */
	public static String displayWithCount(String cc, int count) {
		return display(cc) + " (" + count + ")";
	}
}
