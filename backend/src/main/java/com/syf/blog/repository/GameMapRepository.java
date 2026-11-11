package com.syf.blog.repository;

import com.syf.blog.domain.GameMap;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 地图数据访问接口，由 Spring Data JPA 自动生成实现。
 *
 * <p>{@link GameMap} 是操作的数据库实体，{@link Long} 是地图主键类型。
 * 当前使用继承的 {@code findById(id)} 按主键查询地图：找到时返回实体，
 * 未找到时返回空 Optional，由业务 Service 决定对应的失败提示。
 */
public interface GameMapRepository extends JpaRepository<GameMap, Long> {

}
