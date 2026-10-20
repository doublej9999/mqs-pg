package com.mqspg.config.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mqspg.config.entity.CfgRoute;

/** {@code cfg_route} 数据访问。 */
public interface CfgRouteMapper extends BaseMapper<CfgRoute> {

    /**
     * 按主键读取并**加行锁**（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>用于「激活版本」这类需要「先读当前状态、再据此写多张表」的操作：
     * 不加锁时两个并发请求会各自读到旧的 {@code active_version}，
     * 于是都把自己的目标版本置为 ACTIVE，而「把原 ACTIVE 降级」这一步
     * 两边操作的是同一个旧版本 —— 最终库里留下**两个 ACTIVE 版本**，
     * 且 {@code cfg_route.active_version} 只剩其中一个，另一个成为幽灵。
     *
     * <p>锁的范围是这一行 {@code cfg_route} 记录：同一路由的所有激活请求因此串行化，
     * 不同路由之间互不影响。
     */
    @Select("SELECT * FROM cfg_route WHERE id = #{id} FOR UPDATE")
    CfgRoute selectByIdForUpdate(@Param("id") long id);
}
