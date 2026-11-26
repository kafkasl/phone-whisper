document.addEventListener('DOMContentLoaded', () => {
  initChatDemo();
});

function initChatDemo() {
  const overlayBtn = document.getElementById('overlay-btn');
  const typedText = document.getElementById('chat-typed-text');
  const placeholder = document.getElementById('chat-placeholder');
  const sendBtn = document.getElementById('chat-send-btn');

  if (!overlayBtn || !typedText || !placeholder || !sendBtn) return;

  const demoText = "Running five minutes late. I'll send the summary when I get there.";
  let typingTimer;
  let runId = 0;

  const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

  const setState = (state) => {
    overlayBtn.classList.remove('recording', 'busy');
    if (state) overlayBtn.classList.add(state);
  };

  const clearDemo = () => {
    clearInterval(typingTimer);
    typedText.textContent = '';
    placeholder.style.display = '';
    sendBtn.classList.remove('ready');
    setState('');
  };

  const typeText = (id) => new Promise(resolve => {
    let i = 0;
    placeholder.style.display = 'none';
    typingTimer = setInterval(() => {
      if (id !== runId) {
        clearInterval(typingTimer);
        resolve(false);
        return;
      }
      typedText.textContent += demoText[i] || '';
      i += 1;
      if (i >= demoText.length) {
        clearInterval(typingTimer);
        sendBtn.classList.add('ready');
        resolve(true);
      }
    }, 34);
  });

  const play = async () => {
    const id = ++runId;
    clearDemo();
    await sleep(900);
    if (id !== runId) return;
    setState('recording');
    await sleep(1800);
    if (id !== runId) return;
    setState('busy');
    await sleep(700);
    if (id !== runId) return;
    const completed = await typeText(id);
    if (!completed || id !== runId) return;
    await sleep(2500);
    if (id !== runId) return;
    clearDemo();
    await sleep(1200);
    if (id !== runId) return;
    play();
  };

  play();
  overlayBtn.addEventListener('click', play);
}
