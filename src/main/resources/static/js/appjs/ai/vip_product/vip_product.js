var prefix = '/ai/vip-product';
var _productMap = {};

$(function () { loadList(); });

function loadList() {
    $('#productBody').html('<tr><td colspan="8" style="text-align:center;color:#999">加载中…</td></tr>');
    $.ajax({
        url: prefix + '/list',
        type: 'GET',
        dataType: 'json',
        success: function (r) {
            if (!r || r.code !== 0) {
                $('#productBody').html('<tr><td colspan="8" style="color:red;text-align:center">' +
                    '请求失败：' + (r ? r.msg : '无响应') + '</td></tr>');
                return;
            }
            var list = r.data || [];
            renderTable(list);
        },
        error: function (xhr) {
            $('#productBody').html('<tr><td colspan="8" style="color:red;text-align:center">' +
                '加载失败（HTTP ' + xhr.status + '），请刷新重试</td></tr>');
        }
    });
}

function renderTable(list) {
    _productMap = {};
    if (!list || list.length === 0) {
        $('#productBody').html('<tr><td colspan="8" style="text-align:center;color:#999">暂无套餐，点击右上角新增</td></tr>');
        return;
    }
    var html = '';
    $.each(list, function (i, row) {
        _productMap[row.id] = row;
        var enabledBadge = row.enabled == 1
            ? '<span class="badge badge-success">已启用</span>'
            : '<span class="badge" style="background:#aaa">已禁用</span>';
        var toggleLabel = row.enabled == 1 ? '禁用' : '启用';
        var toggleClass = row.enabled == 1 ? 'btn-warning' : 'btn-success';
        html += '<tr>' +
            '<td>' + (row.sortOrder || 0) + '</td>' +
            '<td><code>' + esc(row.id) + '</code></td>' +
            '<td>' + esc(row.name) + '</td>' +
            '<td>¥' + esc(row.price) + '</td>' +
            '<td>' + (row.durationDays || 0) + ' 天</td>' +
            '<td>' + esc(row.description || '') + '</td>' +
            '<td>' + enabledBadge + '</td>' +
            '<td>' +
            '<button class="btn btn-xs btn-primary" onclick="editProduct(\'' + esc(row.id) + '\')">编辑</button> ' +
            '<button class="btn btn-xs ' + toggleClass + '" onclick="toggleProduct(\'' + esc(row.id) + '\',' + (row.enabled == 1 ? 0 : 1) + ')">' + toggleLabel + '</button> ' +
            '<button class="btn btn-xs btn-danger" onclick="removeProduct(\'' + esc(row.id) + '\')">删除</button>' +
            '</td></tr>';
    });
    $('#productBody').html(html);
}

function addProduct() { openForm(null); }

function editProduct(id) { openForm(_productMap[id]); }

function openForm(row) {
    var isEdit = !!row;
    var html =
        '<div style="padding:16px 20px;">' +
        '<div class="form-group"><label>套餐ID <small style="color:#999">唯一标识，新增后不可修改</small></label>' +
        '<input id="fp_id" class="form-control" placeholder="如 month_vip" value="' + esc(row ? row.id : '') + '"' + (isEdit ? ' readonly' : '') + '></div>' +
        '<div class="form-group"><label>套餐名称</label>' +
        '<input id="fp_name" class="form-control" placeholder="如 月度会员" value="' + esc(row ? row.name : '') + '"></div>' +
        '<div style="display:flex;gap:12px;">' +
        '<div class="form-group" style="flex:1"><label>价格（元）</label>' +
        '<input id="fp_price" class="form-control" type="number" step="0.01" min="0" placeholder="0.00" value="' + esc(row ? row.price : '') + '"></div>' +
        '<div class="form-group" style="flex:1"><label>有效天数</label>' +
        '<input id="fp_days" class="form-control" type="number" min="1" placeholder="30" value="' + esc(row ? row.durationDays : '') + '"></div>' +
        '<div class="form-group" style="flex:1"><label>排序值</label>' +
        '<input id="fp_sort" class="form-control" type="number" min="0" placeholder="0" value="' + esc(row ? row.sortOrder : '0') + '"></div>' +
        '</div>' +
        '<div class="form-group"><label>描述</label>' +
        '<input id="fp_desc" class="form-control" placeholder="显示在套餐卡片上" value="' + esc(row ? row.description : '') + '"></div>' +
        '</div>';

    layer.open({
        type: 1,
        title: isEdit ? '编辑套餐' : '新增套餐',
        area: ['480px', 'auto'],
        content: html,
        btn: ['保存', '取消'],
        yes: function (index) {
            var id    = $.trim($('#fp_id').val());
            var name  = $.trim($('#fp_name').val());
            var price = $.trim($('#fp_price').val());
            var days  = parseInt($('#fp_days').val()) || 0;
            var sort  = parseInt($('#fp_sort').val()) || 0;
            var desc  = $.trim($('#fp_desc').val());
            if (!id || !name || !price) { layer.msg('ID、名称、价格不能为空'); return; }
            $.post(prefix + '/save', { id: id, name: name, price: price, durationDays: days, description: desc, sortOrder: sort },
                function (r) {
                    layer.msg(r.msg || (r.code == 0 ? '保存成功' : '保存失败'));
                    if (r.code == 0) { layer.close(index); loadList(); }
                }, 'json').fail(function () { layer.msg('保存失败'); });
        }
    });
}

function toggleProduct(id, enabled) {
    layer.confirm(enabled == 1 ? '确认启用该套餐？' : '确认禁用？禁用后用户将看不到此套餐。',
        { btn: ['确定', '取消'] }, function (index) {
            layer.close(index);
            $.post(prefix + '/toggle', { id: id, enabled: enabled }, function (r) {
                layer.msg(r.msg);
                if (r.code == 0) loadList();
            }, 'json');
        });
}

function removeProduct(id) {
    layer.confirm('确认删除套餐【' + id + '】？不可撤销。', { btn: ['删除', '取消'] }, function (index) {
        layer.close(index);
        $.post(prefix + '/remove', { id: id }, function (r) {
            layer.msg(r.msg);
            if (r.code == 0) loadList();
        }, 'json');
    });
}

function esc(v) {
    return String(v || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}
