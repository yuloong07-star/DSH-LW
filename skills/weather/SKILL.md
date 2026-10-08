---
name: weather
description: 本机 (LittleWhale) 查天气的规范: 用免 key 的 Open-Meteo (先 geocoding 把地名换成经纬度, 再 forecast 拿实况与三天预报), weather_code 对照表, 以及网不通时如实说。用户问今天 / 明天 / 这周末 / 某个城市的天气时先加载本技能。
---

# 查天气 (Open-Meteo 版)

数据源是 **Open-Meteo**: 免 key, 两步调用, 用 dsh 自带的 `web_fetch` 取 (不要用 `web_search` 转一手)。

## 第一步 · 地名换经纬度

```
https://geocoding-api.open-meteo.com/v1/search?name=<地名>&count=1&language=zh&format=json
```

- `<地名>` 要 URL 编码 (例: `杭州` 是 `%E6%9D%AD%E5%B7%9E`)
- 回的是 `results[]`: `name` / `latitude` / `longitude` / `timezone` / `admin1` / `country`
- **结果为空** = 这个地名它认不出来: 换一个更常见的写法 (加省份 / 加国家) 再试一次, 还不行就问用户,
  不要自己拿一个大概的坐标顶上
- 用户没说是哪座城市时, 先 `lw_location` 拿一次本机位置, 再用反查出来的城市名或直接拿经纬度往下走

## 第二步 · 拿天气

```
https://api.open-meteo.com/v1/forecast?latitude=<纬度>&longitude=<经度>&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3
```

- 要更长就改 `forecast_days` (上限 16), 要小时级就把 `hourly=` 加上
- 经纬度保留 4 位小数就够
- 回里的时间是**当地时区** (`timezone=auto` 按坐标定的), 报时间时说清是当地时间

## `weather_code` 对照表 (报天气必须按它说)

| code | 天气 |
| :-- | :-- |
| 0 | 晴 |
| 1 / 2 / 3 | 少云 / 多云 / 阴 |
| 45 / 48 | 雾 / 结霜雾 |
| 51 / 53 / 55 | 毛毛雨 (小 / 中 / 大) |
| 56 / 57 | 冻毛毛雨 (小 / 强) |
| 61 / 63 / 65 | 小 / 中 / 大雨 |
| 66 / 67 | 冻雨 (小 / 强) |
| 71 / 73 / 75 | 小 / 中 / 大雪 |
| 77 | 雪粒 |
| 80 / 81 / 82 | 阵雨 (小 / 中 / 强) |
| 85 / 86 | 阵雪 (小 / 强) |
| 95 / 96 / 99 | 雷暴 / 雷暴夹小冰雹 / 雷暴夹大冰雹 |

## 报的时候

1. 用户问"今天"就给实况 (`current`) + 今天的最高最低; 问"明天 / 这周末"就给 `daily` 那一天
2. 数字照抄, 不四舍五入成整数也不加"大概": 温度写成 `18°C`, 风速写 `12 km/h`
3. 该提醒的才提醒: 有降水概率 (>50%) 说一句带伞, 温差大说一句加衣, 其余不啰嗦
4. **不编**: 网不通 / 地名认不出来 / 接口报错, 三种都照原样说清楚, 一个数字都不许自己填
