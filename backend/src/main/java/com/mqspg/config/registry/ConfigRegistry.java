package com.mqspg.config.registry;

import com.mqspg.common.model.RouteConfig;

import java.util.List;

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
}
