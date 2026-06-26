package com.bootdo.ai.service;

import com.bootdo.ai.dao.AppUserDao;
import com.bootdo.ai.domain.AppUserDO;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NaviVipService {
    private static final Logger log = LoggerFactory.getLogger(NaviVipService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AppUserDao appUserDao;
    private final WeChatPayClient weChatPayClient;
    private final AlipayClient alipayClient;

    @Value("${navi.vip.mode:disabled}")
    private String mode;

    @Value("${navi.wechat.appId:wx4ac470d6bef3de2f}")
    private String appId;

    public NaviVipService(JdbcTemplate jdbcTemplate, AppUserDao appUserDao, WeChatPayClient weChatPayClient,
                           AlipayClient alipayClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.appUserDao = appUserDao;
        this.weChatPayClient = weChatPayClient;
        this.alipayClient = alipayClient;
    }

    public Map<String, Object> getMembershipStatus(String phone) {
        requireEnabled();
        AppUserDO user = appUserDao.getByPhone(phone);
        if (user == null) {
            throw new IllegalArgumentException("登录用户不存在");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT expires_at FROM navi_vip_membership WHERE user_id=?", user.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        // mysql-connector-java 8.x 默认把 DATETIME 列映射成 java.time.LocalDateTime，不是 java.util.Date
        java.time.LocalDateTime expiresAt = rows.isEmpty() ? null : (java.time.LocalDateTime) rows.get(0).get("expires_at");
        boolean active = expiresAt != null && expiresAt.isAfter(java.time.LocalDateTime.now());
        result.put("active", active);
        result.put("expiresAt", expiresAt == null ? null : expiresAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        return result;
    }

    public List<Map<String, Object>> listProducts() {
        requireEnabled();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,name,price,duration_days,description FROM navi_vip_product WHERE enabled=1 ORDER BY sort_order,id");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("name", row.get("name"));
            item.put("price", ((BigDecimal) row.get("price")).toPlainString());
            item.put("durationDays", row.get("duration_days"));
            item.put("description", row.get("description"));
            result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createOrder(String phone, String productId, String requestedAppId, String payChannel) {
        requireEnabled();
        if (!appId.equals(requestedAppId)) {
            throw new IllegalArgumentException("AppID不匹配");
        }
        if (StringUtils.isBlank(productId)) {
            throw new IllegalArgumentException("请选择会员套餐");
        }
        AppUserDO user = appUserDao.getByPhone(phone);
        if (user == null) {
            throw new IllegalArgumentException("登录用户不存在");
        }
        List<Map<String, Object>> products = jdbcTemplate.queryForList(
                "SELECT id,name,price,duration_days FROM navi_vip_product WHERE id=? AND enabled=1", productId);
        if (products.isEmpty()) {
            throw new IllegalArgumentException("会员套餐不存在或已下架");
        }
        Map<String, Object> product = products.get(0);
        String orderId = createOrderId();
        Date now = new Date();

        if ("mock".equalsIgnoreCase(mode)) {
            jdbcTemplate.update("INSERT INTO navi_vip_order " +
                            "(id,user_id,product_id,amount,status,mock_order,gmt_create,gmt_modified) VALUES (?,?,?,?,?,1,?,?)",
                    orderId, user.getId(), productId, product.get("price"), "PAID", now, now);
            grantMembership(user.getId(), ((Number) product.get("duration_days")).intValue());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", orderId);
            result.put("mock", true);
            result.put("status", "PAID");
            return result;
        }

        if (!"wechat".equalsIgnoreCase(mode)) {
            throw new IllegalStateException("未知的会员支付模式: " + mode);
        }

        if ("alipay".equalsIgnoreCase(payChannel)) {
            if (!alipayClient.isConfigured()) {
                throw new IllegalStateException("支付宝商户密钥未配置完整，请检查 navi.alipay.* 配置");
            }
            BigDecimal price = (BigDecimal) product.get("price");
            String subject = "卫星导航地图-" + product.get("name");
            String orderString;
            try {
                orderString = alipayClient.buildAppPayOrderString(orderId, subject, price.toPlainString());
            } catch (RuntimeException e) {
                log.warn("创建支付宝订单失败 orderId={}: {}", orderId, e.getMessage());
                throw new IllegalStateException("创建支付宝订单失败，请稍后重试");
            }
            jdbcTemplate.update("INSERT INTO navi_vip_order " +
                            "(id,user_id,product_id,amount,status,mock_order,pay_channel,gmt_create,gmt_modified) VALUES (?,?,?,?,?,0,?,?,?)",
                    orderId, user.getId(), productId, product.get("price"), "PENDING", "alipay", now, now);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", orderId);
            result.put("mock", false);
            result.put("status", "PENDING");
            result.put("payChannel", "alipay");
            result.put("aliPayOrderString", orderString);
            return result;
        }

        if (!weChatPayClient.isConfigured()) {
            throw new IllegalStateException("微信支付商户证书未配置完整，请检查 navi.wechat.* 配置");
        }
        BigDecimal price = (BigDecimal) product.get("price");
        long totalFen = price.multiply(new BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        String description = "卫星导航地图-" + product.get("name");
        String prepayId;
        try {
            prepayId = weChatPayClient.createAppPrepay(orderId, description, totalFen).prepayId;
        } catch (IOException e) {
            log.warn("创建微信支付订单失败 orderId={}: {}", orderId, e.getMessage());
            throw new IllegalStateException("创建微信支付订单失败，请稍后重试");
        }
        jdbcTemplate.update("INSERT INTO navi_vip_order " +
                        "(id,user_id,product_id,amount,status,mock_order,pay_channel,gmt_create,gmt_modified) VALUES (?,?,?,?,?,0,?,?,?)",
                orderId, user.getId(), productId, product.get("price"), "PENDING", "wechat", now, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("mock", false);
        result.put("status", "PENDING");
        result.put("payChannel", "wechat");
        result.put("payParams", weChatPayClient.buildAppPayParams(prepayId));
        return result;
    }

    public Map<String, Object> getOrder(String phone, String orderId) {
        requireEnabled();
        AppUserDO user = appUserDao.getByPhone(phone);
        if (user == null) {
            throw new IllegalArgumentException("登录用户不存在");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,status,amount,product_id,pay_channel,gmt_create FROM navi_vip_order WHERE id=? AND user_id=?",
                orderId, user.getId());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("订单不存在");
        }
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        String payChannel = (String) row.get("pay_channel");
        if ("PENDING".equals(status) && "wechat".equalsIgnoreCase(mode)) {
            status = activelyQueryAndSync(orderId, payChannel, status);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", row.get("id"));
        result.put("status", status);
        result.put("amount", ((BigDecimal) row.get("amount")).toPlainString());
        result.put("productId", row.get("product_id"));
        return result;
    }

    /**
     * 本地联调时支付回调无法触达内网，这里在客户端轮询查单时主动调用
     * 微信/支付宝各自的"查询订单"接口同步状态，效果等价于收到异步通知。
     */
    private String activelyQueryAndSync(String orderId, String payChannel, String fallbackStatus) {
        try {
            if ("alipay".equalsIgnoreCase(payChannel)) {
                AlipayClient.TradeQueryResult queried = alipayClient.queryTrade(orderId);
                if ("TRADE_SUCCESS".equals(queried.tradeStatus) || "TRADE_FINISHED".equals(queried.tradeStatus)) {
                    confirmPaid(orderId, queried.tradeNo);
                    return "PAID";
                }
                if ("TRADE_CLOSED".equals(queried.tradeStatus)) {
                    jdbcTemplate.update("UPDATE navi_vip_order SET status='CLOSED',gmt_modified=NOW() WHERE id=? AND status='PENDING'", orderId);
                    return "CLOSED";
                }
                return fallbackStatus;
            }
            WeChatPayClient.OrderQueryResult queried = weChatPayClient.queryByOutTradeNo(orderId);
            if ("SUCCESS".equals(queried.tradeState)) {
                confirmPaid(orderId, queried.transactionId);
                return "PAID";
            }
            if ("CLOSED".equals(queried.tradeState) || "PAYERROR".equals(queried.tradeState)
                    || "REVOKED".equals(queried.tradeState)) {
                jdbcTemplate.update("UPDATE navi_vip_order SET status='CLOSED',gmt_modified=NOW() WHERE id=? AND status='PENDING'", orderId);
                return "CLOSED";
            }
            return fallbackStatus;
        } catch (IOException e) {
            log.warn("查询{}订单状态失败 orderId={}: {}", payChannel, orderId, e.getMessage());
            return fallbackStatus;
        }
    }

    /**
     * 标记订单已支付并发放会员，供异步回调和主动查单两条路径共用；
     * WHERE status='PENDING' 保证两条路径并发触发时只会生效一次。
     */
    @Transactional
    public boolean confirmPaid(String orderId, String transactionId) {
        int updated = jdbcTemplate.update(
                "UPDATE navi_vip_order SET status='PAID',wx_transaction_id=?,gmt_modified=NOW() WHERE id=? AND status='PENDING'",
                transactionId, orderId);
        if (updated == 0) {
            return false;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT o.user_id,p.duration_days FROM navi_vip_order o " +
                        "JOIN navi_vip_product p ON o.product_id = p.id WHERE o.id = ?", orderId);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            grantMembership(row.get("user_id"), ((Number) row.get("duration_days")).intValue());
        }
        return true;
    }

    private void grantMembership(Object userId, int durationDays) {
        jdbcTemplate.update("INSERT INTO navi_vip_membership (user_id,expires_at,gmt_modified) " +
                        "VALUES (?,DATE_ADD(NOW(),INTERVAL ? DAY),NOW()) ON DUPLICATE KEY UPDATE " +
                        "expires_at=DATE_ADD(IF(expires_at>NOW(),expires_at,NOW()),INTERVAL ? DAY),gmt_modified=NOW()",
                userId, durationDays, durationDays);
    }

    private void requireEnabled() {
        if ("disabled".equalsIgnoreCase(mode)) {
            throw new IllegalStateException("Navi会员支付服务未启用");
        }
    }

    private String createOrderId() {
        return "NV" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
