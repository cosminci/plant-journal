import { For, Show, createMemo, createSignal, onMount } from "solid-js";
import type { Component } from "solid-js";
import { Portal } from "solid-js/web";
import * as Journal from "../domain/Journal";
import { ConfirmationDialog } from "./ConfirmationDialog";
import "./catalog-editor.css";

interface DormancySheetProps {
  readonly periods: readonly Journal.DormancyPeriod[];
  readonly pending: boolean;
  readonly onConfirming: (confirming: boolean) => void;
  readonly onSave: (edit: Journal.DormancyEdit) => Promise<Journal.DormancyResult>;
  readonly onClose: () => void;
}

const localTimestamp = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60_000);

const localDay = (value: string) => localTimestamp(new Date(value)).toISOString().slice(0, 10);

const revisedDate = (value: string, original: Journal.Instant) => {
  const timestamp = localTimestamp(new Date(original)).toISOString();
  if (value === timestamp.slice(0, 10)) return original;
  const revised = new Date(`${value}${timestamp.slice(10, -1)}`);
  const localMidnight = Math.floor(localTimestamp(revised).getTime() / 86_400_000) * 86_400_000;
  return localMidnight === Date.parse(value) ? Journal.instant(revised.toISOString()) : undefined;
};

const endTime = (period: Journal.DormancyPeriod) =>
  period.endedAt === null ? Infinity : Date.parse(period.endedAt);

export const DormancySheet: Component<DormancySheetProps> = (props) => {
  let panel!: HTMLElement;
  const dateEdits = new Map<string, { start?: string; end?: string }>();
  const periods = createMemo(() => {
    const newest = [...props.periods].sort((a, b) => endTime(b) - endTime(a));
    // Starting another period must not push an unsaved edit out of the list.
    const selected = [...newest]
      .sort((a, b) => Number(dateEdits.has(b.id)) - Number(dateEdits.has(a.id)))
      .slice(0, 10);
    return newest.filter((period) => selected.includes(period));
  });
  const nextTransition = () =>
    props.periods.some((period) => period.endedAt === null) ? "end" : "start";
  const transitionLabel = (kind: "start" | "end") =>
    kind === "start" ? "Start dormancy" : "End dormancy";
  const [confirming, setConfirming] = createSignal<"start" | "end">();
  const [error, setError] = createSignal<string>();
  const requestTransition = (kind: "start" | "end" | undefined) => {
    setConfirming(kind);
    props.onConfirming(kind !== undefined);
  };
  const confirmTransition = async (kind: "start" | "end") => {
    const result = await props.onSave({ kind, since: Journal.instant(new Date().toISOString()) });
    if (result.kind !== "read") return "Dormancy could not be changed.";
    setError(undefined);
    requestTransition(undefined);
    return undefined;
  };
  const save = async (edit: Journal.DormancyEdit) => {
    setError(undefined);
    const result = await props.onSave(edit);
    if (result.kind === "read") props.onClose();
    else if (result.kind === "invalid")
      setError("Dates must be in order and cannot be in the future.");
    else setError("Dormancy could not be saved.");
  };

  const submit = (event: SubmitEvent) => {
    event.preventDefault();
    const changes: Journal.DormancyPeriod[] = [];
    for (const period of periods()) {
      const dates = dateEdits.get(period.id);
      if (dates === undefined) continue;
      const startedAt =
        dates.start === undefined ? period.startedAt : revisedDate(dates.start, period.startedAt);
      const endedAt =
        period.endedAt === null || dates.end === undefined
          ? period.endedAt
          : revisedDate(dates.end, period.endedAt);
      if (startedAt === undefined || endedAt === undefined) {
        setError("Enter a valid date.");
        return;
      }
      if (startedAt !== period.startedAt || endedAt !== period.endedAt)
        changes.push({ id: period.id, startedAt, endedAt });
    }
    if (changes.length === 0) props.onClose();
    else void save({ kind: "correctDates", periods: changes });
  };

  onMount(() => {
    panel.focus();
  });

  return (
    <>
      <section
        ref={(element) => {
          panel = element;
        }}
        class="catalog-editor"
        tabIndex="-1"
      >
        <header class="catalog-editor__header">
          <h2>Dormancy</h2>
          <button
            class="icon-action sheet-collapse"
            type="button"
            aria-label="Collapse dormancy editor"
            disabled={props.pending}
            onClick={() => {
              props.onClose();
            }}
          >
            <span class="sheet-collapse__icon" aria-hidden="true" />
          </button>
        </header>
        <form class="catalog-editor__form" onSubmit={submit}>
          <button
            id="change-dormancy"
            class="secondary-action dormancy-editor__transition"
            type="button"
            disabled={props.pending}
            onClick={() => {
              requestTransition(nextTransition());
            }}
          >
            {transitionLabel(nextTransition())}
          </button>
          <fieldset class="field-group" disabled={props.pending}>
            <Show when={periods().length > 0} fallback={<p>No dormancy periods yet.</p>}>
              <ol class="dormancy-editor__periods">
                <For each={periods()}>
                  {(period) => (
                    <li>
                      <input
                        type="date"
                        aria-label="Start"
                        value={dateEdits.get(period.id)?.start ?? localDay(period.startedAt)}
                        onInput={(event) => {
                          dateEdits.set(period.id, {
                            ...dateEdits.get(period.id),
                            start: event.currentTarget.value,
                          });
                        }}
                      />
                      <span class="dormancy-editor__arrow" aria-hidden="true">
                        →
                      </span>
                      <Show when={period.endedAt} fallback={<p>Ongoing</p>}>
                        {(endedAt) => (
                          <input
                            type="date"
                            aria-label="End"
                            value={dateEdits.get(period.id)?.end ?? localDay(endedAt())}
                            onInput={(event) => {
                              dateEdits.set(period.id, {
                                ...dateEdits.get(period.id),
                                end: event.currentTarget.value,
                              });
                            }}
                          />
                        )}
                      </Show>
                    </li>
                  )}
                </For>
              </ol>
            </Show>
          </fieldset>
          <Show when={error()}>{(message) => <p role="alert">{message()}</p>}</Show>
          <footer class="catalog-editor__actions">
            <button
              class="secondary-action"
              type="button"
              disabled={props.pending}
              onClick={() => {
                props.onClose();
              }}
            >
              Cancel
            </button>
            <button
              class="primary-action"
              type="submit"
              disabled={props.pending || periods().length === 0}
            >
              {props.pending ? "Saving…" : "Save"}
            </button>
          </footer>
        </form>
      </section>
      <Show when={confirming()} keyed>
        {(kind) => (
          <Portal>
            <ConfirmationDialog
              ariaLabel={transitionLabel(kind)}
              consequenceId="dormancy-consequence"
              title={<>{transitionLabel(kind)}?</>}
              consequence={
                <>This uses the current time. Date edits stay in the sheet until you save them.</>
              }
              confirmLabel={transitionLabel(kind)}
              completed={false}
              returnFocus={{ kind: "restoreOnCancelOnly", returnFocusId: "change-dormancy" }}
              onConfirm={() => confirmTransition(kind)}
              onCancel={() => {
                requestTransition(undefined);
              }}
            />
          </Portal>
        )}
      </Show>
    </>
  );
};
