package com.mqspg.config.registry;

import java.util.List;

import com.mqspg.common.model.RouteConfig;

/**
 * 配置版本注册表（tech-design §12.4）。
 *
 * <p>三件事必须同时成立：
 * <ol>
 *   <li>接收消息时能读到**当前** ACTIVE 版本（PRD §18）；</li>
 *   <li>已绑定版本在批次处理完成前不被替换或回收（PRD §19）；</li>
 *   <li>发布后立即对新消息生效，无需重启。</li>
 * </ol>
 */
public interface ConfigRegistry {

    /**
     * 读取路由当前 ACTIVE 版本号。接收消息时调用。
     *
     * @return 版本号；无 ACTIVE 版本时返回 {@code -1}
     */
    int activeVersion(long routeId);

    /** 按 (routeId, version) 取不可变快照。 */
    RouteConfig get(long routeId, int version);

    /** 取当前 ACTIVE 快照；无则返回 {@code null}。 */
    RouteConfig active(long routeId);

    /** 所有处于 ACTIVE 的路由 id。 */
    List<Long> activeRouteIds();

    /** 重新加载某个路由的 ACTIVE 版本（发布/激活/回滚后调用）。 */
    void refresh(long routeId);

    /** 全量重载。 */
    void refreshAll();

    /**
     * 丢弃某个版本的内存快照（版本被删除后调用）。
     *
     * <p>为什么需要它：快照表只增不删是刻意的（PRD §19 要求历史版本不被动态替换），
     * 但「版本已被删除」和「版本还在、只是不常用了」是两件事 ——
     * 后者必须留着，前者留着会让重试重放时而有配置、时而没有，
     * 表现为「同一条消息重试三次有时成功有时 CONFIG_GONE」。
     *
     * <p>实现上只移除 {@code snapshots} 里的那一项。
     * 调用方负责保证被删的不是 ACTIVE 版本（{@code deleteVersion} 已经拦住）。
     */
    void evict(long routeId, int version);
}
