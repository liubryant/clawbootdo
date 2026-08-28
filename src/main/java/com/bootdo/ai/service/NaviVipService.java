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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final AppAccessTokenService tokenService;

    @Value("${navi.vip.mode:disabled}")
    private String mode;

    @Value("${navi.wechat.appId:wx4ac470d6bef3de2f}")
    private String appId;

    @Value("${agentclaw.alipay.appId:2021006169619056}")
    private String agentClawAlipayAppId;

    @Value("${hossleep.alipay.appId:2021006190660434}")
    private String hossleepAlipayAppId;

    @Value("${apple.iap.bundleId:ai.cjym.agentclaw}")
    private String appleBundleId;

    /** 额外允许的 iOS App。默认仅增加 Navi，不改变 AgentClaw 原 bundleId 配置。 */
    @Value("${apple.iap.additionalBundleIds:cn.navibeidou.beidou}")
    private String appleAdditionalBundleIds;

    public NaviVipService(JdbcTemplate jdbcTemplate, AppUserDao appUserDao, WeChatPayClient weChatPayClient,
                           AlipayClient alipayClient, AppleIapVerifier appleIapVerifier,
                           AppAccessTokenService tokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.appUserDao = appUserDao;
        this.weChatPayClient = weChatPayClient;
        this.alipayClient = alipayClient;
        this.appleIapVerifier = appleIapVerifier;
        this.tokenService = tokenService;
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
        // 返回完整到期时间。沙盒订阅按分钟加速，只返回日期会导致续订后界面看似没有刷新。
        result.put("expiresAt", expiresAt == null ? null : expiresAt.format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("quota", getQuotaConfig("agentclaw"));
        return result;
    }

    /** Apple 订阅状态独立查询，不改变安卓/鸿蒙使用的综合会员返回结构。 */
    public Map<String, Object> getAppleMembershipStatus(String phone) {
        requireEnabled();
        AppUserDO user = appUserDao.getByPhone(phone);
        if (user == null) throw new IllegalArgumentException("登录用户不存在");
        List<Map<String, Object>> rows;
        if (phone.startsWith("ig_") || phone.startsWith("ios_guest_")) {
            rows = jdbcTemplate.queryForList(
                    "SELECT apple_product_id,expires_at FROM navi_apple_entitlement " +
                            "WHERE guest_user_id=? ORDER BY expires_at DESC LIMIT 1", user.getId());
        } else {
            rows = jdbcTemplate.queryForList(
                    "SELECT e.apple_product_id,e.expires_at FROM navi_apple_account_binding b " +
                            "JOIN navi_apple_entitlement e ON e.original_transaction_id=b.original_transaction_id " +
                            "WHERE b.user_id=? ORDER BY e.expires_at DESC LIMIT 1", user.getId());
        }
        return appleStatus(rows.isEmpty() ? null : rows.get(0));
    }

    private Map<String, Object> appleStatus(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        java.time.LocalDateTime expiresAt = row == null ? null : (java.time.LocalDateTime) row.get("expires_at");
        result.put("appleActive", expiresAt != null && expiresAt.isAfter(java.time.LocalDateTime.now()));
        result.put("appleExpiresAt", expiresAt == null ? null : expiresAt.format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("appleProductId", row == null ? null : row.get("apple_product_id"));
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
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM navi_vip_order WHERE product_id=?", Integer.class, id);
        if (orderCount != null && orderCount > 0) {
            throw new IllegalStateException("该套餐已有订单记录，请使用禁用下架");
        }
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
        boolean isHossleep = hossleepAlipayAppId.equals(requestedAppId);
        boolean isNavi = appId.equals(requestedAppId);
        log.info("createOrder appid check: received={}, naviWx={}, acAlipay={}, isNavi={}, isAgentClaw={}",
                requestedAppId == null ? "null" : "***" + requestedAppId.substring(Math.max(0, requestedAppId.length() - 6)),
                "***" + appId.substring(Math.max(0, appId.length() - 6)),
                "***" + agentClawAlipayAppId.substring(Math.max(0, agentClawAlipayAppId.length() - 6)),
                isNavi, isAgentClaw);
        if (!isNavi && !isAgentClaw && !isHossleep) {
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
        String orderId = isAgentClaw ? createAgentClawOrderId() : (isHossleep ? createHossleepOrderId() : createOrderId());
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
                if (isHossleep) {
                    if (!alipayClient.isHossleepConfigured()) {
                        throw new IllegalStateException("时光睡眠支付宝密钥未配置，请检查 hossleep.alipay.* 配置");
                    }
                    subject = "时光睡眠-" + product.get("name");
                    orderString = alipayClient.buildHossleepAppPayOrderString(orderId, subject, price.toPlainString());
                } else if (isAgentClaw) {
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
        AppUserDO authenticatedUser = phone == null ? null : appUserDao.getByPhone(phone);
        if (phone != null && authenticatedUser == null) throw new IllegalArgumentException("登录用户不存在");
        // ig_/ios_guest_ Token 只是匿名 Apple 权益的读取凭证，不是用户主动登录的手机号账号。
        // 旧版客户端可能在继续购买/恢复时携带该 Token，必须仍按游客购买处理，避免把
        // 匿名主体写进账号绑定表，进而误报“该 Apple 订阅已绑定其他账号”。
        AppUserDO loggedInUser = authenticatedUser != null
                && !isAppleGuestPhone(authenticatedUser.getPhone()) ? authenticatedUser : null;

        // 1) 本地校验 JWS：ES256 签名 + Apple Root CA G3 证书链
        AppleIapVerifier.VerifiedTransaction tx = appleIapVerifier.verify(jws);

        // 2) 业务校验：应用归属、退款、商品合法性
        if (!isAllowedAppleBundleId(tx.bundleId)) {
            log.warn("苹果内购 bundleId 不匹配, primary={}, additional={}, actual={}",
                    appleBundleId, appleAdditionalBundleIds, tx.bundleId);
            throw new IllegalArgumentException("支付凭证与当前应用不匹配");
        }
        if (tx.revoked) {
            throw new IllegalArgumentException("该交易已退款，无法开通会员");
        }
        int durationDays = appleDurationDays(tx.productId); // 以已验签的 productId 为准
        if (StringUtils.isBlank(tx.originalTransactionId)) {
            throw new IllegalArgumentException("支付凭证缺少原始交易号");
        }
        if (tx.expiresDate != null && tx.expiresDate <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("该订阅已过期");
        }
        Date expiresAt = tx.expiresDate != null
                ? new Date(tx.expiresDate) : new Date(System.currentTimeMillis() + durationDays * 86400000L);

        // Apple 权益始终建立独立匿名主体，游客无需提供手机号；同一 Apple 订阅在其他设备恢复时
        // 由 originalTransactionId 找回同一主体。登录用户仍同步一份权益到原账号，保持旧逻辑兼容。
        AppUserDO guestUser = ensureAppleGuestUser(tx.originalTransactionId);
        upsertAppleEntitlement(tx, guestUser.getId(), expiresAt);
        setMembershipAtLeast(guestUser.getId(), expiresAt);
        if (loggedInUser != null) {
            boolean exclusiveBinding = bindAppleAccount(loggedInUser.getId(), tx.originalTransactionId);
            // 综合会员表继续供既有安卓/鸿蒙及通用权限逻辑使用，只延长、不缩短。
            setMembershipAtLeast(loggedInUser.getId(), expiresAt);
            // 游客购买后再登录或恢复购买时，把此前 Apple 订单归到当前账号。
            // 条件严格限定 pay_channel='apple'，不会修改微信、支付宝及安卓/鸿蒙订单。
            if (exclusiveBinding) {
                syncAppleOrdersToAccount(loggedInUser.getId(), guestUser.getId());
            }
        }

        // 3) 幂等发放：主键去重，重复交易直接返回当前状态
        String orderId = "AP" + tx.transactionId;
        List<Map<String, Object>> exist = jdbcTemplate.queryForList(
                "SELECT id FROM navi_vip_order WHERE id=?", orderId);
        boolean transactionAlreadyProcessed = !exist.isEmpty();
        if (!transactionAlreadyProcessed) {
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
                    orderId, loggedInUser != null ? loggedInUser.getId() : guestUser.getId(), productIdForRow,
                    amount, "PAID", "apple", tx.transactionId, now, now);
            log.info("苹果内购发放会员成功 user={} guest={} product={} env={} tx={}",
                    loggedInUser == null ? null : loggedInUser.getId(), guestUser.getId(),
                    tx.productId, tx.environment, tx.transactionId);
        } else {
            log.info("苹果内购交易已处理，跳过重复发放 tx={}", tx.transactionId);
        }

        Map<String, Object> result = getMembershipStatus(
                loggedInUser != null ? loggedInUser.getPhone() : guestUser.getPhone());
        result.putAll(appleStatusFromTransaction(tx, expiresAt));
        result.put("guestAccessToken", tokenService.issue(guestUser.getPhone()));
        result.put("accountBindingOptional", true);
        result.put("transactionAlreadyProcessed", transactionAlreadyProcessed);
        return result;
    }

    private Map<String, Object> appleStatusFromTransaction(AppleIapVerifier.VerifiedTransaction tx, Date expiresAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appleActive", expiresAt.after(new Date()));
        result.put("appleExpiresAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(expiresAt));
        result.put("appleProductId", tx.productId);
        return result;
    }

    private boolean bindAppleAccount(Object userId, String originalTransactionId) {
        // 锁住 Apple 权益行，串行化同一订阅的并发绑定；随后检查它是否已属于其他账号。
        // 这样不需要调整通用会员/订单表结构，也不会触碰其他支付渠道。
        List<Map<String, Object>> lockedEntitlements = jdbcTemplate.queryForList(
                "SELECT original_transaction_id FROM navi_apple_entitlement " +
                        "WHERE original_transaction_id=? FOR UPDATE", originalTransactionId);
        if (lockedEntitlements.isEmpty()) {
            throw new IllegalArgumentException("Apple 订阅权益不存在");
        }
        List<Map<String, Object>> bindings = jdbcTemplate.queryForList(
                "SELECT b.user_id,u.phone FROM navi_apple_account_binding b " +
                        "LEFT JOIN ai_app_user u ON u.id=b.user_id WHERE b.original_transaction_id=?",
                originalTransactionId);
        boolean alreadyBoundToCurrentUser = false;
        int accountBindingCount = 0;
        for (Map<String, Object> binding : bindings) {
            // 兼容修复前由游客 Token 误写的绑定记录。它不是手机号账号绑定，既不阻止
            // 当前购买，也不阻止用户之后主动登录同步；保留原记录以避免直接删除线上数据。
            if (isAppleGuestPhone((String) binding.get("phone"))) {
                continue;
            }
            accountBindingCount++;
            if (String.valueOf(userId).equals(String.valueOf(binding.get("user_id")))) {
                alreadyBoundToCurrentUser = true;
            }
        }
        if (accountBindingCount > 0 && !alreadyBoundToCurrentUser) {
            throw new IllegalArgumentException("该 Apple 订阅已绑定其他账号");
        }
        jdbcTemplate.update("INSERT INTO navi_apple_account_binding " +
                        "(user_id,original_transaction_id,gmt_create,gmt_modified) VALUES (?,?,NOW(),NOW()) " +
                        "ON DUPLICATE KEY UPDATE original_transaction_id=VALUES(original_transaction_id),gmt_modified=NOW()",
                userId, originalTransactionId);
        // 兼容历史版本已经产生的重复绑定：不自动删除任何账号数据，也不在归属不明确时迁移订单。
        // 新绑定和正常的单账号重复绑定都会返回 true。
        return accountBindingCount == 0 || (accountBindingCount == 1 && alreadyBoundToCurrentUser);
    }

    /**
     * 把匿名 Apple 主体名下的 Apple 订单迁移到用户主动登录的账号。
     * 仅更新 Apple 渠道订单，通用下单、微信和支付宝订单完全不参与。
     */
    private int syncAppleOrdersToAccount(Object loggedInUserId, Object guestUserId) {
        int updated = jdbcTemplate.update(
                "UPDATE navi_vip_order SET user_id=?,gmt_modified=NOW() " +
                        "WHERE user_id=? AND pay_channel='apple'",
                loggedInUserId, guestUserId);
        if (updated > 0) {
            log.info("Apple 游客订单已同步到登录账号 user={} guest={} orders={}",
                    loggedInUserId, guestUserId, updated);
        }
        return updated;
    }

    private AppUserDO ensureAppleGuestUser(String originalTransactionId) {
        // ai_app_user.phone 是 varchar(20)。匿名 Apple 主体并不是真实手机号，
        // 使用 3 字符前缀 + 17 位 SHA-256 摘要，既保持稳定唯一，又严格控制在 20 字符内。
        String guestPhone = "ig_" + sha256Hex(originalTransactionId).substring(0, 17);
        AppUserDO existing = appUserDao.getByPhone(guestPhone);
        if (existing != null) return existing;
        AppUserDO created = new AppUserDO();
        created.setPhone(guestPhone);
        created.setAppName("agentclaw_ios_guest");
        Date now = new Date();
        created.setGmtCreate(now);
        created.setGmtModified(now);
        appUserDao.save(created);
        return created;
    }

    private boolean isAppleGuestPhone(String phone) {
        return phone != null && (phone.startsWith("ig_") || phone.startsWith("ios_guest_"));
    }

    private void upsertAppleEntitlement(AppleIapVerifier.VerifiedTransaction tx, Object guestUserId, Date expiresAt) {
        jdbcTemplate.update("INSERT INTO navi_apple_entitlement " +
                        "(original_transaction_id,guest_user_id,latest_transaction_id,apple_product_id,expires_at,environment,gmt_create,gmt_modified) " +
                        "VALUES (?,?,?,?,?,?,NOW(),NOW()) ON DUPLICATE KEY UPDATE " +
                        "latest_transaction_id=VALUES(latest_transaction_id),apple_product_id=VALUES(apple_product_id)," +
                        "expires_at=GREATEST(expires_at,VALUES(expires_at)),environment=VALUES(environment),gmt_modified=NOW()",
                tx.originalTransactionId, guestUserId, tx.transactionId, tx.productId, expiresAt, tx.environment);
    }

    private void setMembershipAtLeast(Object userId, Object expiresAt) {
        jdbcTemplate.update("INSERT INTO navi_vip_membership (user_id,expires_at,gmt_modified) VALUES (?,?,NOW()) " +
                        "ON DUPLICATE KEY UPDATE expires_at=GREATEST(expires_at,VALUES(expires_at)),gmt_modified=NOW()",
                userId, expiresAt);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法创建匿名会员标识", e);
        }
    }

    @Transactional
    public Map<String, Object> bindAppleGuestMembership(String loggedInPhone, String guestPhone) {
        requireEnabled();
        // 兼容修复前可能已经签发的 ios_guest_ 凭证；新凭证统一使用长度安全的 ig_ 前缀。
        if (StringUtils.isBlank(guestPhone)
                || (!guestPhone.startsWith("ig_") && !guestPhone.startsWith("ios_guest_"))) {
            throw new IllegalArgumentException("游客权益凭证无效");
        }
        AppUserDO loggedInUser = appUserDao.getByPhone(loggedInPhone);
        AppUserDO guestUser = appUserDao.getByPhone(guestPhone);
        if (loggedInUser == null || guestUser == null) throw new IllegalArgumentException("会员身份不存在");
        List<Map<String, Object>> entitlements = jdbcTemplate.queryForList(
                "SELECT original_transaction_id,expires_at FROM navi_apple_entitlement " +
                        "WHERE guest_user_id=? AND expires_at>NOW() LIMIT 1 FOR UPDATE",
                guestUser.getId());
        if (entitlements.isEmpty()) throw new IllegalArgumentException("没有可同步的 Apple 订阅权益");
        Map<String, Object> entitlement = entitlements.get(0);
        String originalTransactionId = String.valueOf(entitlement.get("original_transaction_id"));
        Object expiresAt = entitlement.get("expires_at");
        boolean exclusiveBinding = bindAppleAccount(loggedInUser.getId(), originalTransactionId);
        setMembershipAtLeast(loggedInUser.getId(), expiresAt);
        int syncedOrders = exclusiveBinding
                ? syncAppleOrdersToAccount(loggedInUser.getId(), guestUser.getId()) : 0;
        Map<String, Object> result = getMembershipStatus(loggedInPhone);
        result.putAll(getAppleMembershipStatus(loggedInPhone));
        result.put("appleOrderSyncCount", syncedOrders);
        return result;
    }

    /** 苹果内购商品 -> 会员天数，权威映射(与 App Store Connect 的 week/month/year 一一对应)。 */
    private int appleDurationDays(String appleProductId) {
        if (appleProductId == null) {
            throw new IllegalArgumentException("缺少商品信息");
        }
        switch (appleProductId) {
            case "cn.agent.vip.week":  return 7;
            case "cn.agent.vip.month": return 30;
            case "cn.agent.vip.year": return 365;
            case "cn.navi.vip.week":  return 7;
            case "cn.navi.vip.month": return 30;
            case "cn.navi.vip.year": return 365;
            default:
                throw new IllegalArgumentException("未知的苹果内购商品: " + appleProductId);
        }
    }

    private boolean isAllowedAppleBundleId(String bundleId) {
        if (StringUtils.isBlank(bundleId)) {
            return false;
        }
        if (bundleId.equals(appleBundleId)) {
            return true;
        }
        if (StringUtils.isBlank(appleAdditionalBundleIds)) {
            return false;
        }
        for (String candidate : appleAdditionalBundleIds.split(",")) {
            if (bundleId.equals(candidate.trim())) {
                return true;
            }
        }
        return false;
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
                        : (orderId.startsWith("HS") ? alipayClient.queryTradeForHossleep(orderId)
                        : alipayClient.queryTrade(orderId));
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

    /** 支付宝回调入账前校验订单归属和金额，防止跨应用/小额订单凭证被误用。 */
    @Transactional
    public boolean confirmAlipayPaid(String orderId, String transactionId, String totalAmount,
                                     String requiredPrefix) {
        if (StringUtils.isBlank(orderId) || !orderId.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("支付宝订单归属不匹配");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT amount,pay_channel FROM navi_vip_order WHERE id=?", orderId);
        if (rows.isEmpty() || !"alipay".equalsIgnoreCase(String.valueOf(rows.get(0).get("pay_channel")))) {
            throw new IllegalArgumentException("支付宝订单不存在");
        }
        BigDecimal expected = (BigDecimal) rows.get(0).get("amount");
        BigDecimal actual;
        try {
            actual = new BigDecimal(totalAmount);
        } catch (Exception e) {
            throw new IllegalArgumentException("支付宝回调金额无效");
        }
        if (expected.compareTo(actual) != 0) {
            throw new IllegalArgumentException("支付宝回调金额不匹配");
        }
        return confirmPaid(orderId, transactionId);
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

    private String createHossleepOrderId() {
        return "HS" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
