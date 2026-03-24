// plugins.js - Управление плагинами через Modrinth API (Исправленная версия v1.0.0)

let serverInfo = { version: "1.21", software: "paper", majorVersion: "1.21" };
let searchTimeout = null;
let cachedPlugins = new Map();
let installedLocalPlugins = [];

// 1. Получаем информацию о сервере с защитой от ошибок
async function fetchServerInfo() {
    const infoDiv = document.getElementById('plugin-server-info');
    if (!infoDiv) return;

    try {
        const res = await fetch('/api/server-info');
        if (res.ok) {
            serverInfo = await res.json();
        } else {
            console.warn("API сервера вернуло ошибку:", res.status);
        }
    } catch (e) {
        console.warn("API сервера недоступно, используем значения по умолчанию", e);
    }

    try {
        const majorVersion = (serverInfo.version || "1.21").split('.').slice(0, 2).join('.');
        serverInfo.majorVersion = majorVersion;

        infoDiv.innerHTML = `
            <div class="server-info-glass">
                <div class="info-item">
                    <span class="icon">⚙️</span>
                    <span data-i18n="plugin_core">Ядро</span> →
                    <span class="highlight">${escapeHtml(serverInfo.software || 'Paper')}</span>
                </div>
                <div class="divider"></div>
                <div class="info-item">
                    <span class="icon">🏷️</span>
                    <span data-i18n="plugin_version">Версия</span> →
                    <span class="highlight">${escapeHtml(serverInfo.version || '1.21')}</span>
                </div>
            </div>
            <div class="server-info-hint" style="margin-top: 10px; font-size: 13px; color: var(--text-muted);">
                <span data-i18n="plugin_hint">Показываем плагины для</span>
                <strong style="color: var(--accent);">${escapeHtml(serverInfo.majorVersion)}</strong>
            </div>
        `;

        if (typeof applyTranslations === 'function') {
            applyTranslations();
        }
    } catch (e) {
        console.error("Критическая ошибка при рендере информации о сервере", e);
        infoDiv.innerHTML = '<div style="color: var(--error-color); padding: 15px; text-align: center;">⚠ Ошибка загрузки информации о сервере</div>';
    }
}

// 2. Получаем установленные плагины из папки plugins
async function fetchInstalledLocalPlugins() {
    try {
        const res = await fetch('/api/files?path=plugins');
        if (res.ok) {
            const files = await res.json();
            installedLocalPlugins = files
                .filter(f => f.type === 'file' && f.name.endsWith('.jar'))
                .map(f => ({
                    fileName: f.name,
                    normalized: f.name.toLowerCase().replace(/[^a-z0-9]/g, '')
                }));
        } else {
            console.warn("Не удалось получить список плагинов из папки plugins");
            installedLocalPlugins = [];
        }
    } catch (e) {
        console.error("Ошибка проверки локальных плагинов:", e);
        installedLocalPlugins = [];
    }
    return installedLocalPlugins;
}

// Поиск совпадений среди локальных плагинов
function getInstalledFileName(project) {
    if (!project || !installedLocalPlugins || installedLocalPlugins.length === 0) return null;
    const titleNorm = (project.title || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    const slugNorm = (project.slug || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    const match = installedLocalPlugins.find(p =>
        p.normalized.includes(titleNorm) ||
        p.normalized.includes(slugNorm) ||
        titleNorm.includes(p.normalized) ||
        slugNorm.includes(p.normalized)
    );
    return match ? match.fileName : null;
}

// Функция поиска плагинов (вызывается из HTML) - УСКОРЕННАЯ до 250мс
function searchPlugins() {
    if (searchTimeout) clearTimeout(searchTimeout);
    searchTimeout = setTimeout(performPluginSearch, 250);
}

// Основная функция поиска плагинов
async function performPluginSearch() {
    const queryInput = document.getElementById('pluginSearch');
    const grid = document.getElementById('plugins-grid');
    if (!grid) return;

    const query = queryInput ? queryInput.value.trim() : '';

    if (!query) {
        grid.innerHTML = '<div class="plugins-placeholder" style="grid-column: 1/-1; text-align: center; color: var(--text-muted);" data-i18n="plugin-not-found">🔍 Введите название плагина для поиска</div>';
        if (typeof applyTranslations === 'function') applyTranslations();
        return;
    }

    // Центрированная красивая анимация загрузки
    grid.innerHTML = `
        <div class="plugin-loading-container">
            <div class="plugin-spinner"></div>
            <div class="plugin-loading-text" data-i18n="plugin-loading">Ищем лучшие плагины...</div>
        </div>
    `;
    if (typeof applyTranslations === 'function') applyTranslations();

    try {
        // Используем правильный формат facets для Modrinth API
        const facets = encodeURIComponent(JSON.stringify([["categories:paper", "categories:bukkit", "categories:spigot", "categories:purpur"]]));

        // Добавляем сортировку по релевантности и популярности
        const url = `https://api.modrinth.com/v2/search?query=${encodeURIComponent(query)}&facets=${facets}&limit=24&index=relevance`;

        const [searchRes] = await Promise.all([
            fetch(url),
            fetchInstalledLocalPlugins()
        ]);

        if (!searchRes.ok) throw new Error(`Modrinth API error: ${searchRes.status}`);
        const data = await searchRes.json();

        if (!data.hits || data.hits.length === 0) {
            grid.innerHTML = '<div class="plugins-empty" style="grid-column: 1/-1; text-align: center; color: var(--text-main);" data-i18n="plugin-not-found">❌ Ничего не найдено. Попробуйте другой запрос.</div>';
            if (typeof applyTranslations === 'function') applyTranslations();
            return;
        }

        // Ждем проверки всех плагинов, прежде чем показать результат
        const projectsData = await Promise.all(data.hits.map(async (project) => {
            return await fetchPluginCompatibility(project);
        }));

        grid.innerHTML = '';
        projectsData.forEach(pData => renderPluginCardHTML(grid, pData));

        if (typeof applyTranslations === 'function') applyTranslations();

    } catch (e) {
        console.error("Ошибка поиска плагинов:", e);
        grid.innerHTML = '<div class="plugins-error" style="grid-column: 1/-1; text-align: center; color: var(--error-color);" data-i18n="plugin-err-load">⚠ Ошибка при поиске плагинов. Проверьте соединение.</div>';
        if (typeof applyTranslations === 'function') applyTranslations();
    }
}

// Проверка совместимости плагина с версией сервера
async function fetchPluginCompatibility(project) {
    let result = {
        project: project,
        isCompatible: false,
        file: null,
        versionInfo: null,
        error: null,
        localFile: getInstalledFileName(project)
    };

    try {
        // Проверяем кэш
        if (cachedPlugins.has(project.project_id)) {
            const cached = cachedPlugins.get(project.project_id);
            result.isCompatible = true;
            result.versionInfo = cached.version;
            result.file = cached.file;
            return result;
        }

        const loaders = encodeURIComponent(JSON.stringify([serverInfo.software, "bukkit", "spigot", "paper", "purpur"]));
        const versionFilter = encodeURIComponent(JSON.stringify([serverInfo.majorVersion]));

        const res = await fetch(`https://api.modrinth.com/v2/project/${project.project_id}/version?loaders=${loaders}&game_versions=${versionFilter}&limit=1`);

        if (res.ok) {
            const versions = await res.json();
            if (versions && versions.length > 0) {
                result.isCompatible = true;
                result.versionInfo = versions[0];
                result.file = versions[0].files.find(f => f.primary) || versions[0].files[0];

                // Сохраняем в кэш
                cachedPlugins.set(project.project_id, { version: versions[0], file: result.file });
            } else {
                result.error = "Нет нужной версии";
            }
        } else {
            result.error = `Ошибка API: ${res.status}`;
        }
    } catch (e) {
        console.error(`Ошибка проверки ${project.project_id}:`, e);
        result.error = "Ошибка запроса";
    }
    return result;
}

// Рендеринг карточки плагина
function renderPluginCardHTML(grid, data) {
    const p = data.project;
    const card = document.createElement('div');
    card.className = 'plugin-card';
    card.setAttribute('data-project-id', p.project_id);

    let metaHtml = '';
    let versionDisplay = '';

    if (data.isCompatible && data.versionInfo) {
        const versionNumber = data.versionInfo.version_number || 'latest';
        versionDisplay = `<span class="version-name">${escapeHtml(versionNumber)}</span>`;
        metaHtml = `<span class="compatible-badge" data-i18n="plugin-compatible">✓ Совместимо</span> ${versionDisplay}`;
    } else {
        const errorMsg = data.error || 'Нет подходящей версии';
        metaHtml = `<span class="incompatible-badge" data-i18n="plugin-incompatible">✗ Несовместимо</span> <span class="version-hint">${escapeHtml(errorMsg)}</span>`;
    }

    // Генерируем "Звезды" на основе популярности (логарифмически)
    let downloads = p.downloads || 0;
    let starsCount = Math.min(5, Math.max(1, Math.ceil(Math.log10(downloads / 50 + 1))));
    let starsHtml = '⭐'.repeat(starsCount);

    let buttonsHtml = '';
    // Кнопка Недоступно
    let unavailableBtn = `<button class="btn-sm btn-danger disabled" disabled style="opacity:0.6; cursor:not-allowed;"><span data-i18n="plugin-unavailable">⛔ Недоступно</span></button>`;

    if (data.localFile) {
        const safeLocalFile = escapeHtml(data.localFile).replace(/'/g, "\\'");
        let actionBtn = data.isCompatible ?
            `<button class="btn-sm btn-reinstall" onclick="actionReinstall('${p.project_id}', '${data.file.url}', '${data.file.filename}', '${safeLocalFile}', this)"><span data-i18n="plugin_reinstall">🔄 Переустановить</span></button>` :
            unavailableBtn;

        buttonsHtml = `
            <div class="plugin-actions">
                ${actionBtn}
                <button class="btn-sm btn-delete" onclick="actionDelete('${p.project_id}', '${safeLocalFile}', this)"><span data-i18n="plugin_delete">🗑️ Удалить</span></button>
            </div>
        `;
    } else {
        buttonsHtml = `
            <div class="plugin-actions">
                ${data.isCompatible && data.file ?
                    `<button class="btn-sm btn-install" id="btn-${p.project_id}" onclick="actionInstall('${p.project_id}', '${data.file.url}', '${data.file.filename}', this)"><span data-i18n="plugin_install">📦 Установить</span></button>` :
                    unavailableBtn}
            </div>
        `;
    }

    const innerDiv = document.createElement('div');
    innerDiv.className = 'plugin-card-inner';
    innerDiv.innerHTML = `
        <div class="plugin-header">
            <img src="${p.icon_url || 'https://cdn.modrinth.com/placeholder.png'}" class="plugin-icon" alt="icon" onerror="this.src='https://via.placeholder.com/50?text=?'">
            <div class="plugin-info">
                <div class="plugin-title">${escapeHtml(p.title)}</div>
                <div class="plugin-author"><span data-i18n="plugin-author">Автор</span>: ${escapeHtml(p.author || 'Unknown')}</div>
            </div>
        </div>
        <div class="plugin-desc">${escapeHtml(p.description || 'Описание отсутствует')}</div>
        <div class="plugin-meta" style="${data.isCompatible ? 'color: var(--info-color);' : 'color: var(--error-color);'}">
            ${metaHtml}
        </div>
        <div class="plugin-stats" style="display: flex; flex-direction: column; gap: 6px; align-items: flex-start; margin-top: auto;">
            <span>📥 ${downloads.toLocaleString()} <span data-i18n="plugin-downloads">загрузок</span></span>
            <span style="color: #ffc800; font-size: 11px; letter-spacing: 2px; text-shadow: 0 0 5px rgba(255, 200, 0, 0.4);">${starsHtml}</span>
        </div>
    `;

    card.appendChild(innerDiv);

    if (buttonsHtml) {
        const actionsDiv = document.createElement('div');
        actionsDiv.innerHTML = buttonsHtml;
        card.appendChild(actionsDiv.firstElementChild);
    }

    grid.appendChild(card);
}

// Установка плагина (исправленный эндпоинт)
async function actionInstall(projectId, url, fileName, btnEl) {
    if (!btnEl) return;

    btnEl.disabled = true;
    btnEl.classList.add('installing');
    const originalText = btnEl.innerHTML;
    btnEl.innerHTML = `<span class="btn-text">⏳ Установка...</span>`;

    try {
        const res = await fetch('/api/install-plugin', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url: url, fileName: fileName })
        });
        const data = await res.json();

        if (data.success) {
            btnEl.classList.remove('installing');
            btnEl.classList.add('installed');
            btnEl.innerHTML = `<span class="btn-text" data-i18n="plugin-success">✅ Установлено!</span>`;
            if (typeof triggerNotification === 'function') {
                triggerNotification('Успех', 'Плагин установлен! Перезагрузите сервер.', 'success');
            }
            // Обновляем список плагинов через 2 секунды
            setTimeout(() => {
                fetchInstalledLocalPlugins();
                performPluginSearch();
            }, 2000);
        } else {
            throw new Error(data.message || 'Неизвестная ошибка');
        }
    } catch (e) {
        console.error("Ошибка установки:", e);
        btnEl.classList.remove('installing');
        btnEl.classList.add('error');
        btnEl.innerHTML = `<span class="btn-text">❌ Ошибка</span>`;
        if (typeof triggerNotification === 'function') {
            triggerNotification('Ошибка', e.message || 'Сбой установки плагина', 'error');
        }
        setTimeout(() => {
            btnEl.disabled = false;
            btnEl.classList.remove('error');
            btnEl.innerHTML = originalText;
            if (typeof applyTranslations === 'function') applyTranslations();
        }, 3000);
    }
}

// Удаление плагина (исправленный эндпоинт)
async function actionDelete(projectId, localFileName, btnEl) {
    if (!confirm(`Удалить плагин ${localFileName} с сервера?`)) return;

    const parent = btnEl.parentElement;
    parent.style.opacity = '0.5';
    parent.style.pointerEvents = 'none';

    try {
        const res = await fetch('/api/files/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: 'plugins/' + localFileName })
        });

        if (res.ok) {
            if (typeof triggerNotification === 'function') {
                triggerNotification('Успех', `Плагин ${localFileName} удалён!`, 'success');
            }
            // Обновляем список локальных плагинов и поиск
            await fetchInstalledLocalPlugins();
            setTimeout(() => performPluginSearch(), 1000);
        } else {
            const errorText = await res.text();
            throw new Error(errorText || "Не удалось удалить файл");
        }
    } catch (e) {
        console.error("Ошибка удаления:", e);
        parent.style.opacity = '1';
        parent.style.pointerEvents = 'all';
        if (typeof triggerNotification === 'function') {
            triggerNotification('Ошибка', e.message, 'error');
        }
    }
}

// Переустановка плагина
async function actionReinstall(projectId, url, fileName, localFileName, btnEl) {
    if (!confirm(`Переустановить плагин ${localFileName}? Старая версия будет удалена.`)) return;

    const originalText = btnEl.innerHTML;
    btnEl.innerHTML = `<span>⏳ Переустановка...</span>`;
    const parent = btnEl.parentElement;
    parent.style.pointerEvents = 'none';

    try {
        // Сначала удаляем старый плагин
        const deleteRes = await fetch('/api/files/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: 'plugins/' + localFileName })
        });

        if (!deleteRes.ok) {
            throw new Error("Не удалось удалить старую версию");
        }

        // Устанавливаем новый
        const installRes = await fetch('/api/install-plugin', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url: url, fileName: fileName })
        });

        const installData = await installRes.json();

        if (installData.success) {
            parent.innerHTML = `<div style="width:100%; text-align:center; padding:12px; color:var(--success); font-weight:bold;" data-i18n="plugin-success">✅ Обновлено!</div>`;
            if (typeof triggerNotification === 'function') {
                triggerNotification('Успех', `Плагин ${fileName} обновлён!`, 'success');
            }
            // Обновляем список локальных плагинов и поиск
            await fetchInstalledLocalPlugins();
            setTimeout(() => performPluginSearch(), 2000);
        } else {
            throw new Error(installData.message || 'Ошибка установки');
        }
    } catch (e) {
        console.error("Ошибка переустановки:", e);
        parent.style.pointerEvents = 'all';
        btnEl.innerHTML = originalText;
        if (typeof triggerNotification === 'function') {
            triggerNotification('Ошибка', e.message, 'error');
        }
    }
}

// Экранирование HTML
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Экспорт функций в глобальную область видимости
window.searchPlugins = searchPlugins;
window.fetchServerInfo = fetchServerInfo;
window.actionInstall = actionInstall;
window.actionDelete = actionDelete;
window.actionReinstall = actionReinstall;

// Автоматическая загрузка информации о сервере при загрузке страницы
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        setTimeout(fetchServerInfo, 500);
        // Предварительная загрузка списка установленных плагинов
        fetchInstalledLocalPlugins();
        // Выполняем начальный поиск (если есть значение в поле)
        const searchInput = document.getElementById('pluginSearch');
        if (searchInput && searchInput.value.trim()) {
            setTimeout(performPluginSearch, 100);
        }
    });
} else {
    setTimeout(fetchServerInfo, 500);
    fetchInstalledLocalPlugins();
    const searchInput = document.getElementById('pluginSearch');
    if (searchInput && searchInput.value.trim()) {
        setTimeout(performPluginSearch, 100);
    }
}