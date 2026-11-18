package com.example.bank.message.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 消息模板读写。模板属于消息产品配置，接口只做增改查。 */
@Repository
public class MessageTemplateRepository {

    private final JdbcTemplate jdbc;

    public MessageTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String code, String name, String eventType, String title,
                     String contentTemplate, boolean enabled) {
        int updated = jdbc.update(
                "UPDATE message_template SET template_name=?, event_type=?, title=?, content_template=?, "
                        + "enabled=?, updated_at=CURRENT_TIMESTAMP WHERE template_code=?",
                name, eventType, title, contentTemplate, enabled ? 1 : 0, code);
        if (updated == 0) {
            jdbc.update("INSERT INTO message_template(template_code, template_name, event_type, title, "
                            + "content_template, enabled) VALUES(?,?,?,?,?,?)",
                    code, name, eventType, title, contentTemplate, enabled ? 1 : 0);
        }
    }

    public Map<String, Object> find(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM message_template WHERE template_code=?", code);
        return rows.isEmpty() ? null : view(rows.get(0));
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM message_template ORDER BY template_code");
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            result.add(view(row));
        }
        return result;
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            view.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return view;
    }
}
