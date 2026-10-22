package com.syf.blog.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "game_map")
@Data
public class GameMap {
    // JPA 使用的无参构造方法。
    // 显式声明后，@Data 会跳过构造方法生成。
    public GameMap() {
    }

    // 地图主键，由数据库自动生成
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 地图名称，对应 name 字段
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    // 每个格子的像素边长，对应 tile_size 字段
    @Column(name = "tile_size", nullable = false)
    private Integer tileSize = 24;

    // 地图种子：外层列表表示行，内层列表表示每一列的材质编号
    // 明确使用 JSON 类型，将整个二维结构存入一个字段
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seed", nullable = false, columnDefinition = "JSON")
    private List<List<Integer>> seed;
}
