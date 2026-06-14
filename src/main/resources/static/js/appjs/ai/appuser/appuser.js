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
            {
                checkbox: true
            },
            {
                field: 'id',
                title: '序号',
                width: 70
            },
            {
                field: 'phone',
                title: '手机号',
                formatter: textFormatter
            },
            {
                field: 'hasPassword',
                title: '登录方式',
                width: 110,
                formatter: loginTypeFormatter
            },
            {
                field: 'decryptedPassword',
                title: '密码',
                width: 120,
                formatter: textFormatter
            },
            {
                field: 'deviceModel',
                title: '手机型号',
                width: 120,
                formatter: textFormatter
            },
            {
                field: 'osVersion',
                title: 'iOS版本',
                width: 100,
                formatter: textFormatter
            },
            {
                field: 'appName',
                title: 'App名称',
                width: 120,
                formatter: textFormatter
            },
            {
                field: 'gmtCreate',
                title: '注册时间',
                width: 160
            },
            {
                field: 'gmtModified',
                title: '更新时间',
                width: 160
            }]
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
        $.each(rows, function (i, row) {
            ids[i] = row['id'];
        });
        $.ajax({
            type: 'POST',
            data: {"ids": ids},
            url: prefix + '/batchRemove',
            success: function (r) {
                if (r.code == 0) {
                    layer.msg(r.msg);
                    reLoad();
                } else {
                    layer.msg(r.msg);
                }
            }
        });
    });
}

function loginTypeFormatter(value) {
    return value ? '验证码 + 密码' : '验证码';
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
