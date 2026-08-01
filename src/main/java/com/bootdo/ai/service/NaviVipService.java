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
    private final AppleIapVerifier appleIapVerifier;

    @Value("${navi.vip.mode:disabled}")
    private String mode;

    @Value("${navi.wechat.appId:wx4ac470d6bef3de2f}")
    private String appId;

    @Value("${agentclaw.alipay.appId:2021006169619056}")
    private String agentClawAlipayAppId;

    @Value("${apple.iap.bundleId:ai.cjym.agentclaw}")
    private String appleBundleId;

    public NaviVipService(JdbcTemplate jdbcTemplate, AppUserDao appUserDao, WeChatPayClient weChatPayClient,
                           AlipayClient alipayClient, AppleIapVerifier appleIapVerifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.appUserDao = appUserDao;
        this.weChatPayClient = weChatPayClient;
        this.alipayClient = alipayClient;
        this.appleIapVerifier = appleIapVerifier;
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
        result.put("quota", getQuotaConfig("agentclaw"));
        return result;
    }

    public Map<String, Object> getQuotaConfig(String appName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT free_video_daily,free_image_daily,vip_video_daily,vip_image_daily,vip_remind_days FROM ai_quota_config WHERE app_name=?", appName);
        Map<String, Object> quota = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            quota.put("freeVideoDaily",  ((Number) row.get("free_video_daily")).intValue());
            quota.put("freeImageDaily",  ((Number) row.get("free_image_daily")).intValue());
            quota.put("vipVideoDaily",   ((Number) row.get("vip_video_daily")).intValue());
            quota.put("vipImageDaily",   ((Number) row.get("vip_image_daily")).intValue());
            quota.put("vipRemindDays",   ((Number) row.get("vip_remind_days")).intValue());
        } else {
            quota.put("freeVideoDaily",  1);
            quota.put("freeImageDaily",  10);
            quota.put("vipVideoDaily",   5);
            quota.put("vipImageDaily",   50);
            quota.put("vipRemindDays",   3);
        }
        return quota;
    }

    public void updateQuotaConfig(String appName, int freeVideo, int freeImage, int vipVideo, int vipImage, int remindDays) {
        int updated = jdbcTemplate.update(
                "UPDATE ai_quota_config SET free_video_daily=?,free_image_daily=?,vip_video_daily=?,vip_image_daily=?,vip_remind_days=?,gmt_modified=NOW() WHERE app_name=?",
                freeVideo, freeImage, vipVideo, vipImage, remindDays, appName);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO ai_quota_config (app_name,free_video_daily,free_image_daily,vip_video_daily,vip_image_daily,vip_remind_days,gmt_create,gmt_modified) VALUES (?,?,?,?,?,?,NOW(),NOW())",
                    appName, freeVideo, freeImage, vipVideo, vipImage, remindDays);
        }
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

    /** 后台管理用：返回全部套餐（含禁用） */
    public List<Map<String, Object>> listAllProducts() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,name,price,duration_days,description,enabled,sort_order," +
                "DATE_FORMAT(gmt_create,'%Y-%m-%d %H:%i') as gmt_create_str " +
                "FROM navi_vip_product ORDER BY sort_order,id");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",          row.get("id"));
            item.put("name",        row.get("name"));
            item.put("price",       ((BigDecimal) row.get("price")).toPlainString());
            item.put("durationDays", row.get("duration_days"));
            item.put("description", row.get("description"));
            item.put("enabled",     row.get("enabled"));
            item.put("sortOrder",   row.get("sort_order"));
            item.put("gmtCreate",   row.get("gmt_create_str"));
            result.add(item);
        }
        return result;
    }

    public void saveProduct(String id, String name, BigDecimal price, int durationDays, String description, int sortOrder) {
        int updated = jdbcTemplate.update(
                "UPDATE navi_vip_product SET name=?,price=?,duration_days=?,description=?,sort_order=?,gmt_modified=NOW() WHERE id=?",
                name, price, durationDays, description, sortOrder, id);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO navi_vip_product (id,name,price,duration_days,description,enabled,sort_order,gmt_create,gmt_modified) VALUES (?,?,?,?,?,1,?,NOW(),NOW())",
                    id, name, price, durationDays, description, sortOrder);
        }
    }

    public void setProductEnabled(String id, int enabled) {
        jdbcTemplate.update("UPDATE navi_vip_product SET enabled=?,gmt_modified=NOW() WHERE id=?", enabled, id);
    }

    public void removeProduct(String id) {
        jdbcTemplate.update("DELETE FROM navi_vip_product WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> createOrder(String phone, String productId, String requestedAppId, String payChannel) {
        return createOrder(phone, productId, requestedAppId, payChannel, null);
    }

    @Transactional
    public Map<String, Object> createOrder(String phone, String productId, String requestedAppId, String payChannel, String platform) {
        requireEnabled();
        boolean isAgentClaw = agentClawAlipayAppId.equals(requestedAppId);
        boolean isNavi = appId.equals(requestedAppId);
        log.info("createOrder appid check: received={}, naviWx={}, acAlipay={}, isNavi={}, isAgentClaw={}",
                requestedAppId == null ? "null" : "***" + requestedAppId.substring(Math.max(0, requestedAppId.length() - 6)),
                "***" + appId.substring(Math.max(0, appId.length() - 6)),
                "***" + agentClawAlipayAppId.substring(Math.max(0, agentClawAlipayAppId.length() - 6)),
                isNavi, isAgentClaw);
        if (!isNavi && !isAgentClaw) {
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
        String orderId = isAgentClaw ? createAgentClawOrderId() : createOrderId();
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
            BigDecimal price = (BigDecimal) product.get("price");
            String subject = "Agent智能助手-" + product.get("name");
            String orderString;
            String h5PayUrl = null;
            try {
                if (isAgentClaw) {
                    if (!alipayClient.isAgentClawConfigured()) {
                        throw new IllegalStateException("AgentClaw支付宝密钥未配置，请检查 agentclaw.alipay.* 配置");
                    }
                    orderString = alipayClient.buildAgentClawAppPayOrderString(orderId, subject, price.toPlainString());
                    if (alipayClient.isAgentClawWapPayEnabled()) {
                        h5PayUrl = alipayClient.buildAgentClawWapPayUrl(orderId, subject, price.toPlainString());
                    }
                } else {
                    if (!alipayClient.isConfigured()) {
                        throw new IllegalStateException("支付宝商户密钥未配置完整，请检查 navi.alipay.* 配置");
                    }
                    subject = "卫星导航地图-" + product.get("name");
                    orderString = alipayClient.buildAppPayOrderString(orderId, subject, price.toPlainString());
                    if (alipayClient.isWapPayEnabled()) {
                        h5PayUrl = alipayClient.buildWapPayUrl(orderId, subject, price.toPlainString());
                    }
                }
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
            // Android 继续使用 aliPayOrderString；仅在支付宝后台已开通手机网站支付时返回 H5 收银台。
            if (StringUtils.isNotBlank(h5PayUrl)) {
                result.put("payUrl", h5PayUrl);
                result.put("h5Url", h5PayUrl);
            }
            if (StringUtils.isNotBlank(platform)) {
                result.put("platform", platform);
            }
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

    /**
     * 苹果内购校验并发放会员。
     * 安全要点：会员时长以 JWS 内经证书链校验的 productId 为准，绝不信任客户端传入的套餐/时长；
     * 以 "AP"+transactionId 作为订单主键实现天然防重放，重复交易只发放一次。
     */
    @Transactional
    public Map<String, Object> verifyApplePurchase(String phone, String requestProductId,
                                                   String requestAppleProductId, String transactionId, String jws) {
        requireEnabled();
        AppUserDO user = appUserDao.getByPhone(phone);
        if (user == null) {
            throw new IllegalArgumentException("登录用户不存在");
        }

        // 1) 本地校验 JWS：ES256 签名 + Apple Root CA G3 证书链
        AppleIapVerifier.VerifiedTransaction tx = appleIapVerifier.verify(jws);

        // 2) 业务校验：应用归属、退款、商品合法性
        if (!appleBundleId.equals(tx.bundleId)) {
            log.warn("苹果内购 bundleId 不匹配, expected={}, actual={}", appleBundleId, tx.bundleId);
            throw new IllegalArgumentException("支付凭证与当前应用不匹配");
        }
        if (tx.revoked) {
            throw new IllegalArgumentException("该交易已退款，无法开通会员");
        }
        int durationDays = appleDurationDays(tx.productId); // 以已验签的 productId 为准

        // 3) 幂等发放：主键去重，重复交易直接返回当前状态
        String orderId = "AP" + tx.transactionId;
        List<Map<String, Object>> exist = jdbcTemplate.queryForList(
                "SELECT id FROM navi_vip_order WHERE id=?", orderId);
        if (exist.isEmpty()) {
            BigDecimal amount = BigDecimal.ZERO;
            String productIdForRow = tx.productId;
            if (StringUtils.isNotBlank(requestProductId)) {
                List<Map<String, Object>> p = jdbcTemplate.queryForList(
                        "SELECT id,price FROM navi_vip_product WHERE id=?", requestProductId);
                if (!p.isEmpty()) {
                    productIdForRow = (String) p.get(0).get("id");
                    amount = (BigDecimal) p.get(0).get("price");
                }
            }
            Date now = new Date();
            jdbcTemplate.update("INSERT INTO navi_vip_order " +
                            "(id,user_id,product_id,amount,status,mock_order,pay_channel,wx_transaction_id,gmt_create,gmt_modified) " +
                            "VALUES (?,?,?,?,?,0,?,?,?,?)",
                    orderId, user.getId(), productIdForRow, amount, "PAID", "apple", tx.transactionId, now, now);
            grantMembership(user.getId(), durationDays);
            log.info("苹果内购发放会员成功 user={} product={} days={} env={} tx={}",
                    user.getId(), tx.productId, durationDays, tx.environment, tx.transactionId);
        } else {
            log.info("苹果内购交易已处理，跳过重复发放 tx={}", tx.transactionId);
        }

        return getMembershipStatus(phone);
    }

    /** 苹果内购商品 -> 会员天数，权威映射(与 App Store Connect 的 week/month/year 一一对应)。 */
    private int appleDurationDays(String appleProductId) {
        if (appleProductId == null) {
            throw new IllegalArgumentException("缺少商品信息");
        }
        switch (appleProductId) {
            case "ai.cjym.agentclaw.vip.week":  return 7;
            case "agent123": return 30;
            case "agent124": return 365;
            default:
                throw new IllegalArgumentException("未知的苹果内购商品: " + appleProductId);
        }
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
                AlipayClient.TradeQueryResult queried = orderId.startsWith("AC")
                        ? alipayClient.queryTradeForAgentClaw(orderId)
                        : alipayClient.queryTrade(orderId);
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

    private String createAgentClawOrderId() {
        return "AC" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
