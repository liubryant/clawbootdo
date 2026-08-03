var prefix = '/inspireplanet/model-config';

$(function () {
    $.get(prefix + '/get', function (config) {
        if (!config) return;
        $('[name=aiProvider]').val(config.aiProvider || 'doubao');
        $('[name=aiBaseUrl]').val(config.aiBaseUrl || 'https://ark.cn-beijing.volces.com/api/v3');
        $('[name=aiApiKey]').val(config.aiApiKey || '');
        $('[name=aiModel]').val(config.aiModel || 'doubao-seed-evolving');
        $('#keyHint').text('API Key 已保存在服务端，仅后台管理员可查看和修改。');
    }).fail(function () { layer.msg('加载配置失败'); });

    $('#saveButton').on('click', function () {
        var data = {
            aiProvider: $('[name=aiProvider]').val().trim(),
            aiBaseUrl: $('[name=aiBaseUrl]').val().trim(),
            aiApiKey: $('[name=aiApiKey]').val().trim(),
            aiModel: $('[name=aiModel]').val().trim()
        };
        $.post(prefix + '/update', data, function (result) {
            if (result.code === 0) {
                $('#keyHint').text('API Key 已保存。');
                layer.msg('保存成功');
            } else layer.msg(result.msg || '保存失败');
        }).fail(function () { layer.msg('请求失败'); });
    });
});
