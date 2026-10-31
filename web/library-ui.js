const library = document.querySelector('.library');
const buttons = [...document.querySelectorAll('.density-controls button')];
for (const button of buttons) {
  button.addEventListener('click', () => {
    const list = document.getElementById('card-list');
    const top = list.getBoundingClientRect().top;
    const anchor = [...list.children].find(row => row.getBoundingClientRect().bottom > top);
    library.dataset.density = button.id === 'density-compact' ? 'compact' : 'regular';
    for (const item of buttons) item.setAttribute('aria-pressed', String(item === button));
    if (anchor) list.scrollTop = anchor.offsetTop - list.offsetTop;
  });
}
