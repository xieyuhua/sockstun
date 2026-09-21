/*
 ============================================================================
 文件名  : Country.java
 说明    : ISO-3166 两位国家码 -> UI 用的展示辅助：国旗 emoji（由两位字母码经
           "区域指示符"字符拼出）加中文国名；国名未知时保守地退回原始国家码。
           特殊哨兵值 GLOBAL / AUTO / UNKNOWN 各有自己的图形。
 ============================================================================
*/

package com.tunvpn;

import java.util.HashMap;
import java.util.Map;

public class Country {
	/* 存在 Preferences.AUTO_SELECT_COUNTRY 里的哨兵值，表示"按实测延迟自动挑最快的
	   国家/地区"（AUTO）与"不限国家/全局"（GLOBAL）。 */
	public static final String GLOBAL = "GLOBAL";
	public static final String AUTO = "AUTO";
	/* 无法解析国家的节点（与 GeoIp.UNKNOWN 一致）。 */
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

	/* 两位国家码 → 旗帜 emoji（区域指示符组合）。未知 / 非 ISO 的值给一个中性符号，
	   保证名字仍然可读。 */
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

	/* 国家码 → 中文名；未知时退回原始代码。 */
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

	/* 选择器用：带节点数量的标签。 */
	public static String displayWithCount(String cc, int count) {
		return display(cc) + " (" + count + ")";
	}

	/* 国家小标签用："可用/总数"。可用 = 已测速且可达（latency >= 0）。 */
	public static String displayWithAvail(String cc, int avail, int total) {
		return display(cc) + " (" + avail + "/" + total + ")";
	}
}
