var prefix = '/ai/today-hotspot';
var hotspotMap = {};

$(function () { loadList(); });

function backToConversation() { window.location.href = '/ai/conversation'; }

function loadList() {
    $('#hotspotBody').html('<tr><td colspan="7" class="text-center">加载中…</td></tr>');
    $.getJSON(prefix + '/list', function (r) {
        if (!r || r.code !== 0) {
            $('#hotspotBody').html('<tr><td colspan="7" class="text-center text-danger">' + esc(r ? r.msg : '加载失败') + '</td></tr>');
            return;
        }
        renderTable(r.data || []);
    }).fail(function () {
        $('#hotspotBody').html('<tr><td colspan="7" class="text-center text-danger">请求失败，请稍后重试</td></tr>');
    });
}

function renderTable(list) {
    hotspotMap = {};
    if (!list.length) {
        $('#hotspotBody').html('<tr><td colspan="7" class="text-center text-muted">暂无热点，点击右上角新增</td></tr>');
        return;
    }
    var html = '';
    $.each(list, function (_, row) {
        hotspotMap[row.id] = row;
        var enabled = row.enabled == 1;
        html += '<tr><td>' + (row.sortOrder || 0) + '</td><td>' + row.id + '</td>' +
            '<td>' + esc(row.title) + '</td><td>' + esc(row.subtitle) + '</td>' +
            '<td style="white-space:pre-wrap;word-break:break-word">' + esc(row.promptTemplate) + '</td>' +
            '<td><span class="label ' + (enabled ? 'label-primary' : 'label-default') + '">' + (enabled ? '已启用' : '已禁用') + '</span></td>' +
            '<td><button class="btn btn-xs btn-primary" onclick="editHotspot(' + row.id + ')">编辑</button> ' +
            '<button class="btn btn-xs ' + (enabled ? 'btn-warning' : 'btn-success') + '" onclick="toggleHotspot(' + row.id + ',' + (enabled ? 0 : 1) + ')">' + (enabled ? '禁用' : '启用') + '</button> ' +
            '<button class="btn btn-xs btn-danger" onclick="removeHotspot(' + row.id + ')">删除</button></td></tr>';
    });
    $('#hotspotBody').html(html);
}

function addHotspot() { openForm(null); }
function editHotspot(id) { openForm(hotspotMap[id]); }

function openForm(row) {
    var html = '<div style="padding:16px 20px">' +
        '<div class="form-group"><label>标题 title</label><input id="fh_title" maxlength="100" class="form-control" value="' + esc(row ? row.title : '') + '"></div>' +
        '<div class="form-group"><label>副标题 subtitle</label><input id="fh_subtitle" maxlength="100" class="form-control" value="' + esc(row ? row.subtitle : '') + '"></div>' +
        '<div class="form-group"><label>对话提示词 promptTemplate</label><textarea id="fh_prompt" maxlength="255" rows="5" class="form-control">' + esc(row ? row.promptTemplate : '') + '</textarea></div>' +
        '<div class="form-group"><label>排序值</label><input id="fh_sort" type="number" class="form-control" value="' + (row ? row.sortOrder : 0) + '"></div></div>';
    layer.open({
        type: 1, title: row ? '编辑今日热点' : '新增今日热点', area: ['600px', 'auto'], content: html,
        btn: ['保存', '取消'], yes: function (index) {
            var data = { id: row ? row.id : '', title: $.trim($('#fh_title').val()),
                subtitle: $.trim($('#fh_subtitle').val()), promptTemplate: $.trim($('#fh_prompt').val()),
                sortOrder: parseInt($('#fh_sort').val()) || 0 };
            if (!data.title || !data.subtitle || !data.promptTemplate) { layer.msg('标题、副标题和提示词不能为空'); return; }
            $.post(prefix + '/save', data, function (r) {
                layer.msg(r.msg || '保存完成');
                if (r.code === 0) { layer.close(index); loadList(); }
            }, 'json').fail(function () { layer.msg('保存失败'); });
        }
    });
}

function toggleHotspot(id, enabled) {
    $.post(prefix + '/toggle', {id: id, enabled: enabled}, function (r) {
        layer.msg(r.msg); if (r.code === 0) loadList();
    }, 'json');
}

function removeHotspot(id) {
    layer.confirm('确认删除该热点？', {btn: ['删除', '取消']}, function (index) {
        layer.close(index);
        $.post(prefix + '/remove', {id: id}, function (r) {
            layer.msg(r.msg); if (r.code === 0) loadList();
        }, 'json');
    });
}

function esc(v) {
    return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
