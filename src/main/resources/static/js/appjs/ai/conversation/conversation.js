var prefix = "/ai/conversation";

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
                deviceName: $('#searchDeviceName').val(),
                conversationType: $('#searchType').val(),
                conversationContent: $('#searchContent').val()
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
                field: 'deviceName',
                title: '请求设备名称',
                formatter: textFormatter
            },
            {
                field: 'conversationType',
                title: '对话类型',
                width: 90,
                formatter: typeFormatter
            },
            {
                field: 'conversationContent',
                title: '对话内容',
                width: 210,
                cellStyle: contentCellStyle,
                formatter: conversationContentFormatter
            },
            {
                field: 'conversationResult',
                title: '对话结果',
                width: 110,
                cellStyle: contentCellStyle,
                formatter: longTextFormatter
            },
            {
                field: 'model',
                title: '模型',
                formatter: textFormatter
            },
            {
                field: 'stream',
                title: '流式',
                width: 70,
                formatter: yesNoFormatter
            },
            {
                field: 'success',
                title: '结果',
                width: 80,
                formatter: successFormatter
            },
            {
                field: 'elapsedMs',
                title: '耗时(ms)',
                width: 90
            },
            {
                field: 'requestIp',
                title: 'IP地址',
                width: 120,
                formatter: textFormatter
            },
            {
                field: 'gmtCreate',
                title: '创建时间',
                width: 160
            },
            {
                title: '操作',
                field: 'id',
                align: 'center',
                width: 80,
                formatter: function (value, row, index) {
                    return '<a class="btn btn-warning btn-sm" href="#" title="删除" onclick="remove(\'' + row.id + '\')"><i class="fa fa-remove"></i></a> ';
                }
            }]
    });
}

function reLoad() {
    $('#exampleTable').bootstrapTable('refresh');
}

function remove(id) {
    layer.confirm('确定要删除选中的记录？', {
        btn: ['确定', '取消']
    }, function () {
        $.ajax({
            url: prefix + "/remove",
            type: "post",
            data: {
                'id': id
            },
            beforeSend: function () {
                index = layer.load();
            },
            success: function (r) {
                layer.close(index);
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

function batchRemove() {
    var rows = $('#exampleTable').bootstrapTable('getSelections');
    if (rows.length == 0) {
        layer.msg("请选择要删除的数据");
        return;
    }
    layer.confirm("确认要删除选中的'" + rows.length + "'条数据吗?", {
        btn: ['确定', '取消']
    }, function () {
        var ids = new Array();
        $.each(rows, function (i, row) {
            ids[i] = row['id'];
        });
        $.ajax({
            type: 'POST',
            data: {
                "ids": ids
            },
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

function typeFormatter(value) {
    if (value == 'IMAGE') {
        return '图片';
    }
    if (value == 'VIDEO') {
        return '视频';
    }
    return '文本';
}

function yesNoFormatter(value) {
    return value == 1 ? '是' : '否';
}

function successFormatter(value) {
    return value == 1 ? '<span class="label label-primary">成功</span>' : '<span class="label label-danger">失败</span>';
}

function textFormatter(value) {
    return escapeHtml(value || '');
}

function longTextFormatter(value) {
    value = value || '';
    var escaped = escapeHtml(value);
    var shortText = value.length > 600 ? escapeHtml(value.substring(0, 600)) + '...' : escaped;
    return '<div class="conversation-cell" title="' + escaped + '">' + shortText + '</div>';
}

function conversationContentFormatter(value) {
    value = addLineNumbers(value || '');
    return longTextFormatter(value);
}

function addLineNumbers(value) {
    var lines = String(value).split(/\r?\n/);
    var result = [];
    var seen = {};
    $.each(lines, function (index, line) {
        line = $.trim(line);
        if (!line) {
            return;
        }
        line = line.replace(/^\d+\.\s*/, '');
        if (seen[line]) {
            return;
        }
        seen[line] = true;
        result.push((result.length + 1) + '. ' + line);
    });
    return result.join('\n');
}

function contentCellStyle() {
    return {
        css: {
            'min-width': '110px',
            'max-width': '280px',
            'vertical-align': 'top'
        }
    };
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
