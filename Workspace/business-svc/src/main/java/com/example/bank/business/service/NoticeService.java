package com.example.bank.business.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;

/** 系统公告服务：负责公告表结构与演示数据播种，并向工作台右栏提供最新公告只读查询。 */
@Service
public class NoticeService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 默认返回条数：前端未传 limit 或传入非法值时使用。 */
    private static final int DEFAULT_LIMIT = 10;

    /** 单次查询条数上限，防止一次拉取过多数据。 */
    private static final int MAX_LIMIT = 50;

    @PostConstruct
    public void initTables() {
        String sqlNotice = "CREATE TABLE IF NOT EXISTS sys_notice (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "title VARCHAR(128) NOT NULL, " +
                "content TEXT, " +
                "level VARCHAR(16) DEFAULT 'INFO', " +
                "publish_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "INDEX idx_publish (publish_time)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";
        jdbcTemplate.execute(sqlNotice);

        // 幂等播种：仅当表内无数据时写入演示公告，重启不会重复插入
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_notice", Integer.class);
        if (count == null || count == 0) {
            seedDemoNotices();
        }

        System.out.println("✅ [NoticeService] 系统公告数据表 sys_notice 校验完毕");
    }

    /** 演示数据：真实项目由运营后台维护。 */
    private void seedDemoNotices() {
        // publish_time 用 NOW() - INTERVAL n DAY 错开，保证列表时间有层次而非同一时刻
        String sql = "INSERT INTO sys_notice (title, content, level, publish_time) "
                + "VALUES (?, ?, ?, NOW() - INTERVAL ? DAY)";

        jdbcTemplate.update(sql,
                "远程视频银行系统升级窗口通知",
                "本周六 00:00 至 04:00 进行核心交易链路版本升级，升级期间视频双录、远程核身与工单流转服务将短暂中断。"
                        + "请各坐席提前告知客户改约时间，升级完成后由运维组统一发布回归验证结论。",
                "WARN", 0);

        jdbcTemplate.update(sql,
                "视频柜员设备巡检安排",
                "各网点视频柜员终端摄像头、拾音器与高拍仪将于本月分批次巡检，巡检顺序按支行提交的排班表执行。"
                        + "发现画面模糊、音频断续或双录文件缺失的设备，请在巡检工单中标注设备编号与故障现象。",
                "INFO", 2);

        jdbcTemplate.update(sql,
                "远程核身合规提示：双录话术与资料留存要求",
                "办理远程开户、大额转账等高频风险业务时，须完整播报风险提示话术并确认客户本人意愿，"
                        + "双录文件需覆盖客户身份核验、业务确认、签字确认三个关键节点，严禁事后补录或拼接。",
                "IMPORTANT", 5);

        jdbcTemplate.update(sql,
                "假期远程银行服务时间调整通知",
                "假期期间远程视频银行服务时间调整为每日 09:00 至 17:00，非服务时段进线自动转入预约队列，"
                        + "由值班坐席在次日上午统一回呼，请提前向客户说明受理时效变化。",
                "INFO", 9);
    }

    /**
     * 查询最新公告。
     * limit 非法（<=0）时回退默认 10，超过上限则钳制为 50。
     * publish_time 在 SQL 侧格式化为 "yyyy-MM-dd HH:mm" 字符串，前端无需二次格式化。
     */
    public List<Map<String, Object>> latest(int limit) {
        int size = limit;
        if (size <= 0) {
            size = DEFAULT_LIMIT;
        }
        if (size > MAX_LIMIT) {
            size = MAX_LIMIT;
        }

        String sql = "SELECT id, title, content, level, "
                + "DATE_FORMAT(publish_time, '%Y-%m-%d %H:%i') AS publish_time "
                + "FROM sys_notice ORDER BY publish_time DESC, id DESC LIMIT ?";
        return jdbcTemplate.queryForList(sql, size);
    }
}
