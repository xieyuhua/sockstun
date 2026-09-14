把 GeoLite2-Country.mmdb 放到本目录（app/src/main/assets/）即可启用离线国家解析。
GeoIp 会优先读取此文件（使用内置的纯 Java mmdb 读取器，无需任何外部依赖）；
文件缺失时回退到原有的在线查询。
可从 MaxMind 免费获取：https://www.maxmind.com/ （GeoLite2 Country 数据库）。
文件名必须保持为 GeoLite2-Country.mmdb。
