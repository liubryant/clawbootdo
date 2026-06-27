var prefix = '/ai/model-config';

var TYPE_LABELS = {
    'TEXT':       { name: '生成文本',  badge: 'primary',   defaultProvider: 'glm',         defaultBase: 'https://open.bigmodel.cn/api/paas/v4', defaultModel: 'glm-4.7' },
    'IMAGE':      { name: '文生图',    badge: 'success',   defaultProvider: 'glm',         defaultBase: 'https://open.bigmodel.cn/api/paas/v4', defaultModel: 'glm-image' },
    'VIDEO':      { name: '生成视频',  badge: 'warning',   defaultProvider: 'glm',         defaultBase: 'https://open.bigmodel.cn/api/paas/v4', defaultModel: 'cogvideox-3' },
    'IMAGE_EDIT': { name: '图生图',    badge: 'danger',    defaultProvider: 'siliconflow', defaultBase: 'https://api.siliconflow.cn/v1',       defaultModel: 'Qwen/Qwen-Image-Edit-2509' }
};

$(function () {
    loadConfigs();
});

function loadConfigs() {
    $.get(prefix + '/list', function (data) {
        renderCards(data);
    }).fail(function () {
        layer.msg('加载配置失败');
    });
}

function renderCards(configs) {
    var $container = $('#configCards');
    $container.empty();

    var order = ['TEXT', 'IMAGE', 'VIDEO', 'IMAGE_EDIT'];
    var cfgMap = {};
    $.each(configs, function (i, c) { cfgMap[c.configType] = c; });

    $.each(order, function (i, type) {
        var info = TYPE_LABELS[type];
        var cfg  = cfgMap[type] || {};
        var provider = cfg.aiProvider || info.defaultProvider;
        var baseUrl  = cfg.aiBaseUrl  || info.defaultBase;
        var apiKey   = cfg.aiApiKey   || '';
        var model    = cfg.aiModel    || info.defaultModel;

        var colClass = (i % 2 === 0) ? 'col-lg-6 col-md-6 col-sm-12' : 'col-lg-6 col-md-6 col-sm-12';
        var card =
            '<div class="' + colClass + '">' +
            '  <div class="ibox model-card">' +
            '    <div class="ibox-title">' +
            '      <h5>' + escHtml(info.name) + ' <span class="badge badge-' + info.badge + ' badge-type">' + type + '</span></h5>' +
            '    </div>' +
            '    <div class="ibox-body">' +
            '      <input type="hidden" name="configType" value="' + type + '">' +
            '      <div class="form-group row">' +
            '        <label class="col-sm-3 col-form-label">服务商</label>' +
            '        <div class="col-sm-9"><input type="text" class="form-control" name="aiProvider" placeholder="glm / siliconflow / openai ..." value="' + escHtml(provider) + '"></div>' +
            '      </div>' +
            '      <div class="form-group row">' +
            '        <label class="col-sm-3 col-form-label">接口地址</label>' +
            '        <div class="col-sm-9"><input type="text" class="form-control" name="aiBaseUrl" placeholder="https://..." value="' + escHtml(baseUrl) + '"></div>' +
            '      </div>' +
            '      <div class="form-group row">' +
            '        <label class="col-sm-3 col-form-label">API Key</label>' +
            '        <div class="col-sm-9"><input type="text" class="form-control apikey-input" name="aiApiKey" placeholder="留空则使用 application.yml 本地配置" value="' + escHtml(apiKey) + '"></div>' +
            '      </div>' +
            '      <div class="form-group row">' +
            '        <label class="col-sm-3 col-form-label">模型</label>' +
            '        <div class="col-sm-9"><input type="text" class="form-control" name="aiModel" value="' + escHtml(model) + '"></div>' +
            '      </div>' +
            '      <div class="save-bar text-right">' +
            '        <button class="btn btn-sm btn-primary" onclick="saveOne(\'' + type + '\', this)"><i class="fa fa-save"></i> 保存</button>' +
            '      </div>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        $container.append(card);
    });
}

function saveOne(type, btn) {
    var $card = $(btn).closest('.model-card');
    var data = {
        configType: type,
        aiProvider: $card.find('[name=aiProvider]').val().trim(),
        aiBaseUrl:  $card.find('[name=aiBaseUrl]').val().trim(),
        aiApiKey:   $card.find('[name=aiApiKey]').val().trim(),
        aiModel:    $card.find('[name=aiModel]').val().trim(),
        enabled:    1
    };
    $.post(prefix + '/update', data, function (r) {
        if (r.code === 0) {
            layer.msg('保存成功');
        } else {
            layer.msg('保存失败: ' + r.msg);
        }
    }).fail(function () {
        layer.msg('请求失败');
    });
}

function saveAll() {
    var tasks = [];
    $('.model-card').each(function () {
        var $card = $(this);
        var type = $card.find('[name=configType]').val();
        tasks.push({
            configType: type,
            aiProvider: $card.find('[name=aiProvider]').val().trim(),
            aiBaseUrl:  $card.find('[name=aiBaseUrl]').val().trim(),
            aiApiKey:   $card.find('[name=aiApiKey]').val().trim(),
            aiModel:    $card.find('[name=aiModel]').val().trim(),
            enabled:    1
        });
    });

    var done = 0;
    var failed = 0;
    $.each(tasks, function (i, data) {
        $.post(prefix + '/update', data, function (r) {
            done++;
            if (r.code !== 0) failed++;
            if (done === tasks.length) {
                layer.msg(failed === 0 ? '全部保存成功' : '部分保存失败，请检查日志');
            }
        }).fail(function () {
            done++;
            failed++;
            if (done === tasks.length) {
                layer.msg('部分保存失败');
            }
        });
    });
}

function escHtml(v) {
    return String(v || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
