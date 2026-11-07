package com.jjy.infrastructure.dictionary;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 数据字典服务：启动时全量加载到内存 + Caffeine 缓存
 * <p>
 * 提供：
 * 1. 元数据接口（/meta/dictionaries），供前端启动时一次拉取
 * 2. 管理接口（/system/dict/*），供管理后台 CRUD 字典类型/字典项
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class DictionaryService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Map<String, List<DictItem>> dictCache = new ConcurrentHashMap<>();
    private final Cache<String, List<DictItem>> cache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT dictionary_id, type_code, code, label, sort_order, enabled " +
                            "FROM t_data_dictionary_item ORDER BY dictionary_id, type_code, sort_order");
            Map<String, List<DictItem>> grouped = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                DictItem it = new DictItem();
                it.setDictionaryId((String) r.get("dictionary_id"));
                it.setTypeCode((String) r.get("type_code"));
                it.setCode((String) r.get("code"));
                it.setLabel((String) r.get("label"));
                Object en = r.get("enabled");
                it.setEnabled(en == null || Boolean.TRUE.equals(en) || "1".equals(String.valueOf(en)));
                Object so = r.get("sort_order");
                it.setSortOrder(so == null ? 0 : ((Number) so).intValue());
                grouped.computeIfAbsent(it.getDictionaryId(), k -> new ArrayList<>()).add(it);
            }
            dictCache.clear();
            dictCache.putAll(grouped);
            log.info("[Dictionary] 加载数据字典 {} 个", dictCache.size());
        } catch (Exception e) {
            log.warn("[Dictionary] 启动期加载失败（首次启动时正常）: {}", e.getMessage());
        }
    }

    public List<DictItem> getByDictionaryId(String dictionaryId) {
        return cache.get(dictionaryId, k -> {
            List<DictItem> items = dictCache.get(k);
            if (items == null) {
                items = loadFromDb(k);
            }
            return items == null ? Collections.emptyList() : items;
        });
    }

    public List<DictItem> getByTypeCode(String typeCode) {
        return dictCache.values().stream()
                .flatMap(List::stream)
                .filter(it -> typeCode.equals(it.getTypeCode()))
                .filter(DictItem::getEnabled)
                .sorted(Comparator.comparingInt(DictItem::getSortOrder))
                .collect(Collectors.toList());
    }

    public Map<String, List<DictItem>> getAll() {
        return dictCache;
    }

    public void refresh() {
        cache.invalidateAll();
        warmUp();
    }

    // ============================================================
    // 管理接口 CRUD
    // ============================================================

    private static final RowMapper<DictType> TYPE_ROW_MAPPER = (rs, i) -> {
        DictType t = new DictType();
        t.setDictionaryId(rs.getString("dictionary_id"));
        t.setDescription(rs.getString("description"));
        t.setCreatedAt(rs.getTimestamp("created_at"));
        t.setUpdatedAt(rs.getTimestamp("updated_at"));
        return t;
    };

    private static final RowMapper<DictItem> ITEM_ROW_MAPPER = (rs, i) -> {
        DictItem it = new DictItem();
        it.setDictionaryId(rs.getString("dictionary_id"));
        it.setTypeCode(rs.getString("type_code"));
        it.setTypeName(rs.getString("type_name"));
        it.setCode(rs.getString("code"));
        it.setLabel(rs.getString("label"));
        it.setEnabled(rs.getBoolean("enabled"));
        it.setSortOrder(rs.getInt("sort_order"));
        it.setCreatedAt(rs.getTimestamp("created_at"));
        it.setUpdatedAt(rs.getTimestamp("updated_at"));
        return it;
    };

    public List<DictType> listAllTypes() {
        return jdbcTemplate.query(
                "SELECT dictionary_id, description, created_at, updated_at FROM t_data_dictionary ORDER BY dictionary_id",
                TYPE_ROW_MAPPER);
    }

    public List<DictType> pagedListTypes(int pageNum, int pageSize, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT dictionary_id, description, created_at, updated_at FROM t_data_dictionary WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (dictionary_id LIKE ? OR description LIKE ?) ");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY dictionary_id LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add(Math.max(0, (pageNum - 1) * pageSize));
        return jdbcTemplate.query(sql.toString(), TYPE_ROW_MAPPER, args.toArray());
    }

    public int countTypes(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_data_dictionary", Integer.class);
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_data_dictionary WHERE dictionary_id LIKE ? OR description LIKE ?",
                Integer.class, "%" + keyword + "%", "%" + keyword + "%");
    }

    public DictType getType(String dictionaryId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT dictionary_id, description, created_at, updated_at FROM t_data_dictionary WHERE dictionary_id = ?",
                    TYPE_ROW_MAPPER, dictionaryId);
        } catch (Exception e) {
            return null;
        }
    }

    public void upsertType(DictType t) {
        jdbcTemplate.update(
                "INSERT INTO t_data_dictionary (dictionary_id, description) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE description = VALUES(description)",
                t.getDictionaryId(), t.getDescription());
        refresh();
    }

    public int deleteType(String dictionaryId) {
        int cnt = jdbcTemplate.update("DELETE FROM t_data_dictionary_item WHERE dictionary_id = ?", dictionaryId);
        jdbcTemplate.update("DELETE FROM t_data_dictionary WHERE dictionary_id = ?", dictionaryId);
        refresh();
        return cnt;
    }

    public List<DictItem> pagedListItems(int pageNum, int pageSize, String dictionaryId, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT dictionary_id, type_code, type_name, code, label, enabled, sort_order, created_at, updated_at " +
                        "FROM t_data_dictionary_item WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (dictionaryId != null && !dictionaryId.isEmpty()) {
            sql.append(" AND dictionary_id = ? ");
            args.add(dictionaryId);
        }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (code LIKE ? OR label LIKE ? OR type_code LIKE ?) ");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY dictionary_id, sort_order LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add(Math.max(0, (pageNum - 1) * pageSize));
        return jdbcTemplate.query(sql.toString(), ITEM_ROW_MAPPER, args.toArray());
    }

    public int countItems(String dictionaryId, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM t_data_dictionary_item WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (dictionaryId != null && !dictionaryId.isEmpty()) {
            sql.append(" AND dictionary_id = ? ");
            args.add(dictionaryId);
        }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (code LIKE ? OR label LIKE ? OR type_code LIKE ?) ");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        return jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
    }

    public List<DictItem> listItemsByDictId(String dictionaryId) {
        return jdbcTemplate.query(
                "SELECT dictionary_id, type_code, type_name, code, label, enabled, sort_order, created_at, updated_at " +
                        "FROM t_data_dictionary_item WHERE dictionary_id = ? ORDER BY sort_order",
                ITEM_ROW_MAPPER, dictionaryId);
    }

    public void upsertItem(DictItem it) {
        jdbcTemplate.update(
                "INSERT INTO t_data_dictionary_item (dictionary_id, type_code, type_name, code, label, enabled, sort_order) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), label = VALUES(label), " +
                        "enabled = VALUES(enabled), sort_order = VALUES(sort_order)",
                it.getDictionaryId(), it.getTypeCode(), it.getTypeName(), it.getCode(),
                it.getLabel(), it.getEnabled() == null ? Boolean.TRUE : it.getEnabled(),
                it.getSortOrder() == null ? 0 : it.getSortOrder());
        refresh();
    }

    public int deleteItem(String dictionaryId, String typeCode, String code) {
        int cnt = jdbcTemplate.update(
                "DELETE FROM t_data_dictionary_item WHERE dictionary_id = ? AND type_code = ? AND code = ?",
                dictionaryId, typeCode, code);
        refresh();
        return cnt;
    }

    private List<DictItem> loadFromDb(String dictionaryId) {
        try {
            return jdbcTemplate.query(
                    "SELECT dictionary_id, type_code, code, label, sort_order, enabled " +
                            "FROM t_data_dictionary_item WHERE dictionary_id = ? ORDER BY sort_order",
                    (rs, i) -> {
                        DictItem it = new DictItem();
                        it.setDictionaryId(rs.getString("dictionary_id"));
                        it.setTypeCode(rs.getString("type_code"));
                        it.setCode(rs.getString("code"));
                        it.setLabel(rs.getString("label"));
                        it.setEnabled(rs.getBoolean("enabled"));
                        it.setSortOrder(rs.getInt("sort_order"));
                        return it;
                    },
                    dictionaryId);
        } catch (Exception e) {
            log.warn("[Dictionary] 加载字典 {} 失败", dictionaryId, e);
            return Collections.emptyList();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DictItem implements Serializable {
        private String dictionaryId;
        private String typeCode;
        private String typeName;
        private String code;
        private String label;
        private Boolean enabled;
        private Integer sortOrder;
        private java.sql.Timestamp createdAt;
        private java.sql.Timestamp updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DictType implements Serializable {
        private String dictionaryId;
        private String description;
        private java.sql.Timestamp createdAt;
        private java.sql.Timestamp updatedAt;
    }
}
