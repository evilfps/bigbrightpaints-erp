const workflowTabs = document.querySelectorAll(".workflow-tab");
const workflowPanes = document.querySelectorAll(".workflow-pane");
const compareButtons = document.querySelectorAll(".compare-button");
const comparePanes = document.querySelectorAll(".compare-pane");
const revealTargets = document.querySelectorAll(".section, .footer-cta");

function activatePane(buttons, panes, key, value) {
  buttons.forEach((button) => {
    button.classList.toggle("active", button.dataset[key] === value);
  });
  panes.forEach((pane) => {
    pane.classList.toggle("active", pane.dataset[key] === value);
  });
}

workflowTabs.forEach((tab) => {
  tab.addEventListener("click", () => activatePane(workflowTabs, workflowPanes, "step", tab.dataset.step));
});

compareButtons.forEach((button) => {
  button.addEventListener("click", () =>
    activatePane(compareButtons, comparePanes, "compare", button.dataset.compare),
  );
});

const observer = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add("in-view");
      }
    });
  },
  { threshold: 0.18 },
);

revealTargets.forEach((target) => {
  target.classList.add("reveal");
  observer.observe(target);
});
