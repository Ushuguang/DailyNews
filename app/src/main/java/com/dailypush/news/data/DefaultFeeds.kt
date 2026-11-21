package com.dailypush.news.data

/**
 * 内置订阅源。
 *
 * 三类来源：
 *  - rss ：标准 XML 订阅源（已做新鲜度校验，最新条目必须是当天）
 *  - web ：官网首页 / 电子版网页直接抽取标题链接（人民网、新华网的 RSS 已停更，但官网仍是服务端渲染）
 *  - hot ：各家热榜接口（微博 / 百度 / 抖音 / 知乎 / B站 / 头条 / 腾讯）
 *
 * 用户可以在「订阅源」页自由增删。
 */
object DefaultFeeds {

    val all: List<Feed> = listOf(
        // ---- 时政要闻（网页型） ----
        Feed(
            id = "web_xinhua_home",
            name = "新华社·要闻",
            url = "https://www.news.cn/",
            category = "时政",
            kind = Feed.KIND_WEB
        ),
        Feed(
            id = "web_rmrb_epaper",
            name = "人民日报·电子版",
            url = "http://paper.people.com.cn/rmrb/pc/layout/{yyyyMM}/{dd}/node_01.html",
            category = "时政",
            kind = Feed.KIND_WEB
        ),
        Feed(
            id = "web_people_home",
            name = "人民网·要闻",
            url = "http://www.people.com.cn/",
            category = "时政",
            kind = Feed.KIND_WEB
        ),

        // ---- 要闻 / 时政（RSS） ----
        Feed("chinanews_import", "中新网·要闻", "https://www.chinanews.com.cn/rss/importnews.xml", "要闻"),
        Feed("chinanews_china", "中新网·国内", "https://www.chinanews.com.cn/rss/china.xml", "要闻"),
        Feed("chinanews_scroll", "中新网·滚动", "https://www.chinanews.com.cn/rss/scroll-news.xml", "要闻"),
        Feed("jiemian", "界面新闻", "https://a.jiemian.com/index.php?m=article&a=rss", "要闻"),

        // ---- 国际 ----
        Feed("chinanews_world", "中新网·国际", "https://www.chinanews.com.cn/rss/world.xml", "国际"),
        Feed("un_news", "联合国新闻", "https://news.un.org/feed/subscribe/zh/news/all/rss.xml", "国际"),
        Feed("wapo_world", "华盛顿邮报·国际", "https://feeds.washingtonpost.com/rss/world", "国际"),

        // ---- 财经 ----
        Feed("chinanews_finance", "中新网·财经", "https://www.chinanews.com.cn/rss/finance.xml", "财经"),
        Feed("wallstreetcn", "华尔街见闻", "https://dedicated.wallstreetcn.com/rss.xml", "财经"),
        Feed("eastmoney", "东方财富", "http://rss.eastmoney.com/rss_partener.xml", "财经"),

        // ---- 社会 ----
        Feed("chinanews_society", "中新网·社会", "https://www.chinanews.com.cn/rss/society.xml", "社会"),

        // ---- 游戏 ----
        Feed("game_gcores", "机核", "https://www.gcores.com/rss", "游戏"),
        Feed("game_ign", "IGN中国", "https://www.ign.com.cn/rss", "游戏"),
        Feed("game_yystv", "游研社", "https://www.yystv.cn/rss/feed", "游戏"),

        // ---- 科技 ----
        Feed("ithome", "IT之家", "https://www.ithome.com/rss/", "科技"),
        Feed("sspai", "少数派", "https://sspai.com/feed", "科技"),
        Feed("solidot", "Solidot", "https://www.solidot.org/index.rss", "科技"),
        Feed("geekpark", "极客公园", "https://www.geekpark.net/rss", "科技"),
        Feed("tmtpost", "钛媒体", "https://www.tmtpost.com/feed", "科技"),
        Feed("ifanr", "爱范儿", "https://www.ifanr.com/feed", "科技"),
        Feed("leiphone", "雷峰网", "https://www.leiphone.com/feed", "科技"),

        // ---- 生活 ----
        Feed("chinanews_health", "中新网·健康", "https://www.chinanews.com.cn/rss/health.xml", "生活"),
        Feed("chinanews_sports", "中新网·体育", "https://www.chinanews.com.cn/rss/sports.xml", "生活"),
        Feed("chinanews_edu", "中新网·教育", "https://www.chinanews.com.cn/rss/edu.xml", "生活"),

        // ---- 热搜榜 ----
        Feed(
            id = "hot_weibo",
            name = "微博热搜",
            url = "https://weibo.com/ajax/side/hotSearch",
            category = "热搜",
            kind = Feed.KIND_HOT
        ),
        Feed(
            id = "hot_baidu",
            name = "百度热搜",
            url = "https://top.baidu.com/board?tab=realtime",
            category = "热搜",
            kind = Feed.KIND_HOT
        ),
        Feed(
            id = "hot_douyin",
            name = "抖音热搜",
            url = "https://www.douyin.com/aweme/v1/web/hot/search/list/",
            category = "热搜",
            kind = Feed.KIND_HOT
        ),
        Feed(
            id = "hot_zhihu",
            name = "知乎热榜",
            url = "https://api.zhihu.com/topstory/hot-list",
            category = "热搜",
            kind = Feed.KIND_HOT
        ),
        Feed(
            id = "hot_bilibili",
            name = "B站热搜",
            url = "https://api.bilibili.com/x/web-interface/search/square",
            category = "热搜",
            kind = Feed.KIND_HOT
        ),
        Feed(
            id = "hot_bilibili_video",
            name = "B站热门视频",
            url = "https://api.bilibili.com/x/web-interface/popular",
            category = "热搜",
            kind = Feed.KIND_HOT
        ),
        Feed(
            id = "hot_toutiao",
            name = "今日头条热榜",
            url = "https://www.toutiao.com/hot-event/hot-board/",
            category = "热搜",
            kind = Feed.KIND_HOT
        ),
        Feed(
            id = "hot_tencent",
            name = "腾讯新闻热榜",
            url = "https://i.news.qq.com/gw/event/hot_ranking_list",
            category = "热搜",
            kind = Feed.KIND_HOT
        )
    )
}
