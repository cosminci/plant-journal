import type { Component } from "solid-js";

export const SleepIcon: Component<{ readonly class: string }> = (props) => (
  <svg
    class={props.class}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.5"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path d="M10 5a8.5 8.5 0 1 0 8.7 12.6A7.8 7.8 0 0 1 10 5Z" />
    <path d="M12.5 10.5H15l-2.5 3H15M16 7h3l-3 3.5h3M18.5 2h4l-4 4h4" />
  </svg>
);
