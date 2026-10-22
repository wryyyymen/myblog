package com.syf.blog.repository;

import com.syf.blog.domain.GameMap;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 地图数据访问接口，由 Spring Data JPA 自动生成实现。
 *
 * GameMap：操作的数据库实体。
 * Long：实体主键 id 的 Java 类型。
 */
public interface GameMapRepository extends JpaRepository<GameMap, Long> {

}