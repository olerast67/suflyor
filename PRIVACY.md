# Privacy

**Suflyor works entirely on your phone. It has no internet access at all.**

- **No network.** The app does not request the `INTERNET` permission, so Android does not let it connect anywhere. You can check this in the app's full permission list (Permissions → ⋮ → All permissions), or with `aapt2 dump permissions Suflyor-<version>.apk`.
- **Microphone.** Audio is used only while the prompter listens: during a session over another app or a rehearsal inside the app. During a session over another app, a notification is shown (on Android 13 and newer only if you allowed notifications; otherwise the session is listed among active apps in Quick Settings). On Android 12 and newer the system also shows its microphone indicator. The speech model built into the app turns the sound into words. Audio is never recorded to a file, stored or sent anywhere.
- **Accessibility service.** It is used only during a prompter session over another app:
  - Android keeps giving the prompter the microphone while another app (Instagram, TikTok, the camera) records video;
  - the floating window is shown;
  - volume and remote-control keys move the text. This is on by default with a preset set of keys, and you can turn it off or reassign the keys in Settings → Remote and buttons.

  It **cannot read screen content** (`canRetrieveWindowContent="false"`). The only thing it notices is the name of the app in front, which goes to the in-app journal. Between sessions it receives no key presses and ignores window events.
- **Display over other apps** (optional). This permission is only a fallback for showing the prompter window when the accessibility service is off. It gives no access to what is on the screen.
- **Your scripts** are stored in the app's private storage on the phone and are excluded from cloud backup. They leave the phone only if you share them yourself, or when Android's own phone-to-phone transfer moves your apps to a new device.
- **The log** (Settings → Log) keeps technical events in memory, including recognized phrases, so that problems can be diagnosed. It stays there until the app's process ends or you tap Clear, and it leaves the app only if you tap Share or Copy yourself. Release builds don't copy it to the system log. The bundled speech library may still write technical warnings there (for example, about audio resampling), but never your words.
- **No analytics, no ads, no accounts, no tracking.** The app contains no third-party SDKs of this kind.

Questions: open an issue on GitHub.

---

# Конфиденциальность

**Суфлёр работает целиком на телефоне. Доступа в интернет у него нет вообще.**

- **Сеть.** Приложение не запрашивает разрешение `INTERNET`, поэтому Android не даёт ему никуда подключаться.
- **Микрофон** работает только тогда, когда суфлёр слушает: во время сессии поверх другого приложения или репетиции внутри приложения. Во время сессии поверх другого приложения видно уведомление (на Android 13 и новее — если уведомления разрешены; иначе сессия видна в списке активных приложений в шторке). На Android 12 и новее система вдобавок показывает свой индикатор микрофона. Звук превращается в слова встроенной моделью прямо на телефоне. Он не записывается в файлы, не хранится и никуда не отправляется.
- **Служба специальных возможностей** работает только во время сессии поверх другого приложения. Она нужна для трёх вещей:
  - чтобы Android не отнимал микрофон, пока другое приложение снимает видео;
  - чтобы показывать окно поверх других приложений;
  - чтобы кнопки громкости и пульта управляли текстом. Это включено по умолчанию для стандартного набора кнопок; выключить или переназначить их можно в Настройках → «Пульт и кнопки».

  **Содержимое экрана она читать не может.** Она видит только название приложения на экране и пишет его в журнал. Между сессиями она не получает нажатий и не обрабатывает события окон.
- **Показ поверх других окон** (по желанию) — запасной способ показать окно суфлёра, если служба специальных возможностей выключена. Доступа к содержимому экрана это разрешение не даёт.
- **Сценарии** хранятся в закрытой папке приложения и не попадают в облачную резервную копию. Телефон они покидают, только если ты сам ими поделишься или перенесёшь приложения на новый телефон штатным переносом Android.
- **Журнал** хранится в памяти, в том числе распознанные фразы, пока процесс приложения не завершится или пока ты не нажмёшь «Очистить». Из приложения он уходит, только если ты сам нажмёшь «Поделиться» или «Скопировать». Релизная сборка не копирует журнал в системный лог. Встроенная библиотека распознавания может писать туда технические предупреждения, например о пересчёте частоты звука, но не твои слова.
- **Аналитики, рекламы, аккаунтов и слежки нет.**
