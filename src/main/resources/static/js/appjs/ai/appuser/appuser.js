var prefix = "/ai/appuser";

$(function () {
    load();
});

$('#exampleTable').on('load-success.bs.table', function (e, data) {
    if (data.total && !data.rows.length) {
        $('#exampleTable').bootstrapTable('selectPage').bootstrapTable('refresh');
    }
});

function load() {
    $('#exampleTable').bootstrapTable({
        method: 'get',
        url: prefix + "/list",
        iconSize: 'outline',
        toolbar: '#exampleToolbar',
        striped: true,
        dataType: "json",
        pagination: true,
        singleSelect: false,
        pageSize: 10,
        pageNumber: 1,
        sidePagination: "server",
        queryParams: function (params) {
            return {
                limit: params.limit,
                offset: params.offset,
                sort: 'gmt_create',
                order: 'desc',
                phone: $('#searchPhone').val()
            };
        },
        columns: [
            { checkbox: true },
            { field: 'id', title: '序号', width: 60 },
            { field: 'phone', title: '手机号', formatter: textFormatter },
            { field: 'hasPassword', title: '登录方式', width: 110, formatter: loginTypeFormatter },
            { field: 'decryptedPassword', title: '密码', width: 110, formatter: textFormatter },
            { field: 'vipActive', title: '会员状态', width: 90, formatter: vipStatusFormatter },
            { field: 'vipExpiresAt', title: '到期时间', width: 110, formatter: vipExpiresFormatter },
            { field: 'deviceModel', title: '手机型号', width: 120, formatter: textFormatter },
            { field: 'osVersion', title: '版本', width: 100, formatter: textFormatter },
            { field: 'appName', title: 'App', width: 110, formatter: textFormatter },
            { field: 'gmtCreate', title: '注册时间', width: 155 },
            { field: 'gmtModified', title: '更新时间', width: 155 }
        ]
    });
}

function reLoad() {
    $('#exampleTable').bootstrapTable('refresh');
}

function add() {
    layer.open({
        type: 2,
        title: '新增用户',
        maxmin: true,
        shadeClose: false,
        area: ['500px', '360px'],
        content: prefix + '/add'
    });
}

function batchRemove() {
    var rows = $('#exampleTable').bootstrapTable('getSelections');
    if (rows.length == 0) {
        layer.msg("请选择要删除的数据");
        return;
    }
    layer.confirm("确认要删除选中的 " + rows.length + " 条数据吗？", {
        btn: ['确定', '取消']
    }, function () {
        var ids = [];
        $.each(rows, function (i, row) { ids[i] = row['id']; });
        $.ajax({
            type: 'POST',
            data: {"ids": ids},
            url: prefix + '/batchRemove',
            success: function (r) {
                layer.msg(r.msg);
                if (r.code == 0) reLoad();
            }
        });
    });
}

function openQuotaConfig() {
    $.get(prefix + '/quota-config', function (r) {
        if (r.code !== 0) {
            layer.msg(r.msg || '加载额度配置失败');
            return;
        }
        var d = r.data;
        var html = '<div style="padding:20px;">' +
            '<div style="display:flex;gap:24px;">' +
            '<div style="flex:1;">' +
            '<p style="color:#888;font-weight:600;margin-bottom:12px;">普通用户（每日）</p>' +
            '<div class="form-group"><label>视频生成次数</label>' +
            '<input type="number" id="qFreeVideo" class="form-control" min="0" value="' + (d.freeVideoDaily||0) + '"></div>' +
            '<div class="form-group"><label>图片生成张数</label>' +
            '<input type="number" id="qFreeImage" class="form-control" min="0" value="' + (d.freeImageDaily||0) + '"></div>' +
            '</div>' +
            '<div style="flex:1;">' +
            '<p style="color:#c8a96e;font-weight:600;margin-bottom:12px;">会员（每日）</p>' +
            '<div class="form-group"><label>视频生成次数</label>' +
            '<input type="number" id="qVipVideo" class="form-control" min="0" value="' + (d.vipVideoDaily||0) + '"></div>' +
            '<div class="form-group"><label>图片生成张数</label>' +
            '<input type="number" id="qVipImage" class="form-control" min="0" value="' + (d.vipImageDaily||0) + '"></div>' +
            '</div></div>' +
            '<div class="form-group" style="margin-top:12px;">' +
            '<label>会员到期提前提醒天数</label>' +
            '<input type="number" id="qRemindDays" class="form-control" min="0" value="' + (d.vipRemindDays||3) + '">' +
            '</div></div>';

        layer.open({
            type: 1,
            title: 'AgentClaw 额度配置',
            area: ['500px', 'auto'],
            content: html,
            btn: ['保存', '取消'],
            yes: function (index) {
                $.post(prefix + '/quota-config', {
                    freeVideo:  parseInt($('#qFreeVideo').val())  || 0,
                    freeImage:  parseInt($('#qFreeImage').val())  || 0,
                    vipVideo:   parseInt($('#qVipVideo').val())   || 0,
                    vipImage:   parseInt($('#qVipImage').val())   || 0,
                    remindDays: parseInt($('#qRemindDays').val()) || 0
                }, function (res) {
                    layer.msg(res.msg || (res.code == 0 ? '保存成功' : '保存失败'));
                    if (res.code == 0) layer.close(index);
                }).fail(function () { layer.msg('保存失败'); });
            }
        });
    }).fail(function () { layer.msg('加载额度配置失败'); });
}

function loginTypeFormatter(value) {
    return value ? '验证码 + 密码' : '验证码';
}

function vipStatusFormatter(value) {
    if (value === true || value === 1) {
        return '<span class="badge badge-success">会员</span>';
    }
    return '<span class="badge badge-default" style="background:#aaa">普通</span>';
}

function vipExpiresFormatter(value) {
    if (!value) return '<span style="color:#bbb">—</span>';
    var today = new Date().toISOString().slice(0, 10);
    var color = value < today ? '#e74c3c' : '#27ae60';
    return '<span style="color:' + color + '">' + escapeHtml(value) + '</span>';
}

function textFormatter(value) {
    return escapeHtml(value || '');
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
