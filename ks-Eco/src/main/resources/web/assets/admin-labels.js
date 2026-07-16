document.addEventListener('DOMContentLoaded', () => {
  const title = document.getElementById('guidanceBankSummary')?.closest('.card')?.querySelector('h3');
  if (title) title.textContent = '\u5F00\u53D1\u94F6\u884C';
});
