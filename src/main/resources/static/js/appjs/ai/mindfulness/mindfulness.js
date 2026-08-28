var prefix = '/ai/mindfulness';

$(function () { loadList(); });

function loadList() {
    $.get(prefix + '/list', function (r) {
        var rows = r && r.code === 0 ? (r.data || []) : [];
        if (!rows.length) {
            $('#mindfulnessBody').html('<tr><td colspan="6" class="text-center text-muted">暂无正念音频</td></tr>');
            return;
        }
        var html = '';
        $.each(rows, function (_, row) {
            var enabled = Number(row.enabled) === 1;
            html += '<tr><td>' + (row.sortOrder || 0) + '</td><td>' + esc(row.title) + '</td>' +
                '<td>' + esc(row.originalName || '') + '</td><td>' + formatSize(row.sizeBytes) + '</td>' +
                '<td>' + (enabled ? '<span class="label label-primary">已启用</span>' : '<span class="label label-default">已禁用</span>') + '</td>' +
                '<td><a class="btn btn-xs btn-info" target="_blank" href="/api/mindfulness/' + row.id + '/download">试听/下载</a> ' +
                '<button class="btn btn-xs ' + (enabled ? 'btn-warning' : 'btn-success') + '" onclick="toggleItem(' + row.id + ',' + (enabled ? 0 : 1) + ')">' + (enabled ? '禁用' : '启用') + '</button> ' +
                '<button class="btn btn-xs btn-danger" onclick="removeItem(' + row.id + ')">删除</button></td></tr>';
        });
        $('#mindfulnessBody').html(html);
    }, 'json').fail(function () {
        $('#mindfulnessBody').html('<tr><td colspan="6" class="text-center text-danger">加载失败</td></tr>');
    });
}

function openUpload() {
    var html = '<div style="padding:18px 22px">' +
        '<div class="form-group"><label>标题</label><input id="mf_title" class="form-control" maxlength="100" placeholder="例如：快速入睡"></div>' +
        '<div class="form-group"><label>排序</label><input id="mf_sort" class="form-control" type="number" value="0"></div>' +
        '<div class="form-group"><label>音频文件</label><input id="mf_audio" type="file" accept="audio/*,.mp3,.m4a,.aac,.wav,.ogg"></div>' +
        '<div id="mf_progress" class="text-info"></div></div>';
    layer.open({type: 1, title: '上传正念音频', area: ['500px', 'auto'], content: html, btn: ['开始上传', '取消'], yes: function (index) {
        var title = $.trim($('#mf_title').val());
        var file = $('#mf_audio')[0].files[0];
        if (!title || !file) { layer.msg('请填写标题并选择音频'); return; }
        if (file.size > 30 * 1024 * 1024) { layer.msg('音频不能超过30MB'); return; }
        var data = new FormData();
        data.append('title', title); data.append('sortOrder', parseInt($('#mf_sort').val()) || 0); data.append('audio', file);
        $('#mf_progress').text('正在上传，请勿关闭窗口…');
        $.ajax({url: prefix + '/upload', type: 'POST', data: data, processData: false, contentType: false, dataType: 'json',
            success: function (r) { layer.msg(r.msg || '上传完成'); if (r.code === 0) { layer.close(index); loadList(); } else { $('#mf_progress').text(''); } },
            error: function () { $('#mf_progress').text(''); layer.msg('上传失败'); }
        });
    }});
}

function toggleItem(id, enabled) {
    $.post(prefix + '/toggle', {id: id, enabled: enabled}, function (r) { layer.msg(r.msg); if (r.code === 0) loadList(); }, 'json');
}

function removeItem(id) {
    layer.confirm('删除后音频文件也会从服务器移除，确认删除？', {btn: ['确认删除', '取消']}, function (index) {
        layer.close(index);
        $.post(prefix + '/remove', {id: id}, function (r) { layer.msg(r.msg); if (r.code === 0) loadList(); }, 'json');
    });
}

function formatSize(bytes) {
    var n = Number(bytes || 0); return n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : (n / 1024).toFixed(1) + ' KB';
}

function esc(v) {
    return String(v || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
