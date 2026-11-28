import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { App } from "../../src/app/App";
import {
  instant,
  milliseconds,
  nickname,
  pesticideName,
  pesticideId,
  plantId,
} from "../../src/domain/Journal";
import * as Journal from "../../src/domain/Journal";
import type {
  GetOperationsResult,
  GetPlantsResult,
  OperationWindow,
  AttentionSample,
  PlantId,
  NewPlantDetails,
} from "../../src/domain/Journal";
import * as JournalFixtures from "./JournalTestSupport";

const unavailableFicusAttention: Journal.AttentionProjection = {
  measuredAt: instant("2026-01-01T00:00:00Z"),
  plants: [
    {
      plant: JournalFixtures.ficus().id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    },
  ],
};

const renderJournal = (journal: ReturnType<typeof JournalFixtures.buildJournal>) =>
  render(() => (
    <App
      plants={journal}
      operations={journal}
      attention={journal}
      substrates={journal}
      pesticideCatalog={journal}
      photos={JournalFixtures.noopPhotoClient}
    />
  ));

describe("browsing the journal", () => {
  it("reports a garden load failure when dormancy history cannot be read", async () => {
    const journal = {
      ...JournalFixtures.buildJournal({
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      }),
      getDormancy: () => Promise.resolve({ kind: "failed" as const, reason: new Error("offline") }),
    };
    renderJournal(journal);
    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
  });

  it.each([
    { kind: "read", periods: [] },
    { kind: "failed", reason: new Error("offline") },
  ] satisfies Journal.DormancyResult[])(
    "keeps a dormancy save after an older $kind read",
    async (staleRead) => {
      const base = JournalFixtures.buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      });
      let resolveOperations!: (result: GetOperationsResult) => void;
      const operationRead = new Promise<GetOperationsResult>((resolve) => {
        resolveOperations = resolve;
      });
      const getOperations = vi.fn((id: PlantId, window: OperationWindow) =>
        base.getOperations(id, window),
      );
      const getDormancy = vi.fn((id: PlantId) => base.getDormancy(id));
      const journal = {
        ...base,
        getOperations,
        getDormancy,
        editPlant: () => Promise.resolve({ kind: "edited" as const }),
        editDormancy: () =>
          Promise.resolve({
            kind: "read" as const,
            periods: [{ id: "winter", startedAt: instant("2026-03-01T00:00:00Z"), endedAt: null }],
          }),
      };
      renderJournal(journal);
      const edit = await screen.findByRole("button", { name: "Edit Fern" });
      getOperations.mockReturnValueOnce(operationRead);
      getDormancy.mockResolvedValueOnce(staleRead);
      fireEvent.click(edit);
      const editor = () => within(screen.getByRole("dialog", { name: "Plant editor" }));
      fireEvent.click(editor().getByRole("button", { name: "Save" }));
      await waitFor(() => {
        expect(getDormancy).toHaveBeenCalledTimes(2);
      });
      fireEvent.click(edit);
      fireEvent.click(editor().getByRole("button", { name: "Dormancy" }));
      const dormancy = within(screen.getByRole("dialog", { name: "Dormancy" }));
      fireEvent.click(dormancy.getByRole("button", { name: "Start dormancy" }));
      fireEvent.click(
        within(screen.getByRole("alertdialog")).getByRole("button", { name: "Start dormancy" }),
      );
      await dormancy.findByRole("button", { name: "End dormancy" });
      resolveOperations(JournalFixtures.operationsPage());
      fireEvent.click(dormancy.getByRole("button", { name: "Cancel" }));
      await waitFor(() => expect(editor().getByRole("button", { name: "Dormancy" })).toHaveFocus());
      fireEvent.click(editor().getByRole("button", { name: "Collapse plant editor" }));
      await waitFor(() => expect(edit).toHaveFocus());
      expect(screen.getByLabelText("Dormant")).toBeInTheDocument();
    },
  );

  it("uses saved dormancy immediately and keeps an invalid correction in the editor", async () => {
    const period = {
      id: "winter",
      startedAt: instant("2026-03-01T00:00:00Z"),
      endedAt: null,
    };
    const journal = {
      ...JournalFixtures.buildJournal({
        attentionProjection: unavailableFicusAttention,
        getPlantsResults: [
          { kind: "read", plants: [JournalFixtures.ficus(), JournalFixtures.monstera()] },
        ],
        getOperationsByPlantId: {
          p1: [JournalFixtures.operationsPage()],
          p2: [JournalFixtures.operationsPage()],
        },
      }),
      editDormancy: vi.fn(
        (_plant: PlantId, edit: Journal.DormancyEdit): Promise<Journal.DormancyResult> => {
          switch (edit.kind) {
            case "start":
              return Promise.resolve({ kind: "read", periods: [period] });
            case "end":
              return Promise.resolve({
                kind: "read",
                periods: [{ ...period, endedAt: edit.since }],
              });
            case "correctDates":
              return Promise.resolve({ kind: "invalid" });
          }
        },
      ),
    };
    renderJournal(journal);
    fireEvent.click(await screen.findByRole("button", { name: "Edit Fern" }));
    const editor = screen.getByRole("dialog", { name: "Plant editor" });
    const plantFields = within(editor);
    const launcher = plantFields.getByRole("button", { name: "Dormancy" });
    expect(launcher.parentElement).toBe(
      plantFields.getByRole("button", { name: "Save" }).parentElement,
    );
    expect(launcher.parentElement).toBe(
      plantFields.getByRole("button", { name: "Move to cemetery" }).parentElement,
    );
    const nicknameInput = plantFields.getByLabelText("Nickname (optional)");
    fireEvent.input(nicknameInput, { target: { value: "Unsaved nickname" } });
    fireEvent.click(launcher);
    const panel = screen.getByRole("dialog", { name: "Dormancy" });
    const fields = within(panel);
    expect(fields.getByText("No dormancy periods yet.")).toBeVisible();
    expect(fields.getByRole("button", { name: "Save" })).toBeDisabled();
    const start = fields.getByRole("button", { name: "Start dormancy" });
    fireEvent.click(start);
    fireEvent.keyDown(screen.getByRole("alertdialog"), { key: "Escape" });
    await waitFor(() => expect(start).toHaveFocus());
    expect(journal.editDormancy).not.toHaveBeenCalled();
    let resolveStart!: (result: Journal.DormancyResult) => void;
    journal.editDormancy.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveStart = resolve;
      }),
    );
    fireEvent.click(start);
    const confirmStart = within(screen.getByRole("alertdialog")).getByRole("button", {
      name: "Start dormancy",
    });
    vi.setSystemTime(new Date("2026-11-28T08:25:30.123Z"));
    try {
      fireEvent.click(confirmStart);
      expect(journal.editDormancy).toHaveBeenCalledExactlyOnceWith("p1", {
        kind: "start",
        since: "2026-11-28T08:25:30.123Z",
      });
    } finally {
      vi.useRealTimers();
    }
    expect(confirmStart).toBeDisabled();
    resolveStart({ kind: "read", periods: [period] });
    await fields.findByRole("button", { name: "End dormancy" });
    const dormant = screen.getByRole("complementary", { name: "Dormant" });
    expect(within(dormant).getByRole("img", { name: "Dormancy icon" })).toBeVisible();
    expect(dormant).toHaveTextContent(/\d+ days/);
    expect(
      screen.queryByRole("button", { name: "Watering attention details for Fern" }),
    ).toBeNull();
    const history = fields;
    expect(history.getByText("Ongoing")).toBeVisible();
    fireEvent.input(history.getByLabelText("Start", { exact: true }), {
      target: { value: "" },
    });
    fireEvent.click(history.getByRole("button", { name: "Save" }));
    expect(await history.findByRole("alert")).toHaveTextContent("Enter a valid");
    fireEvent.input(history.getByLabelText("Start", { exact: true }), {
      target: { value: "2026-01-01" },
    });
    fireEvent.click(history.getByRole("button", { name: "Save" }));
    expect(await history.findByRole("alert")).toHaveTextContent("Dates must be in order");
    expect(history.getByLabelText("Start", { exact: true })).toHaveValue("2026-01-01");
    fireEvent.click(history.getByRole("button", { name: "End dormancy" }));
    const ending = within(screen.getByRole("alertdialog"));
    const confirmEnd = ending.getByRole("button", { name: "End dormancy" });
    journal.editDormancy.mockResolvedValueOnce({ kind: "invalid" });
    fireEvent.click(confirmEnd);
    expect(await ending.findByRole("alert")).toHaveTextContent("Dormancy could not be changed");
    fireEvent.click(confirmEnd);
    const endDate = await history.findByLabelText("End", { exact: true });
    const startDate = history.getByLabelText("Start", { exact: true });
    expect(startDate.closest("li")).toBe(endDate.closest("li"));
    expect(startDate.closest("li")).toHaveTextContent(/^→$/);
    expect(startDate).toHaveAttribute("type", "date");
    expect(endDate).toHaveAttribute("type", "date");
    expect(startDate).toHaveValue("2026-01-01");
    expect(screen.queryByLabelText("Dormant")).toBeNull();
    expect(screen.queryByRole("img", { name: /dorman/i })).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Watering attention details for Fern" }),
    ).toBeVisible();
    const closed = history;
    fireEvent.input(endDate, { target: { value: "" } });
    fireEvent.click(closed.getByRole("button", { name: "Save" }));
    expect(await closed.findByRole("alert")).toHaveTextContent("Enter a valid");
    fireEvent.input(endDate, { target: { value: "2026-03-03" } });
    journal.editDormancy.mockResolvedValueOnce({ kind: "failed", reason: new Error("offline") });
    fireEvent.click(closed.getByRole("button", { name: "Save" }));
    expect(await closed.findByRole("alert")).toHaveTextContent("Dormancy could not be saved.");
    fireEvent.click(closed.getByRole("button", { name: "Cancel" }));
    await waitFor(() => {
      expect(launcher).toHaveFocus();
    });
    expect(nicknameInput).toHaveValue("Unsaved nickname");
    fireEvent.click(launcher);
    expect(screen.getByLabelText("Start", { exact: true })).not.toHaveValue("2026-01-01");
    fireEvent.click(screen.getByRole("button", { name: "Collapse dormancy editor" }));
    await waitFor(() => {
      expect(launcher).toHaveFocus();
    });
  });

  it("shows only the latest ten periods, newest end first, and saves changed dates together", async () => {
    const ongoing = { id: "ongoing", startedAt: instant("2025-12-01T12:00:00Z"), endedAt: null };
    const periods = Array.from({ length: 11 }, (_, i) => ({
      id: `period-${String(i)}`,
      startedAt: instant(`2025-${String(i + 1).padStart(2, "0")}-01T12:00:00Z`),
      endedAt: instant(`2025-${String(i + 1).padStart(2, "0")}-02T12:00:00Z`),
    }));
    const journal = {
      ...JournalFixtures.buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      }),
      getDormancy: () => Promise.resolve({ kind: "read" as const, periods: [...periods, ongoing] }),
      editDormancy: vi.fn(() =>
        Promise.resolve({ kind: "read" as const, periods: [...periods, ongoing] }),
      ),
    };
    renderJournal(journal);
    fireEvent.click(await screen.findByRole("button", { name: "Edit Fern" }));
    const launcher = screen.getByRole("button", { name: "Dormancy" });
    fireEvent.click(launcher);
    const initial = within(screen.getByRole("dialog", { name: "Dormancy" }));
    const firstStart = initial.getAllByLabelText<HTMLInputElement>("Start", { exact: true })[0];
    if (firstStart === undefined) throw new Error("Expected an editable start date");
    const original = firstStart.value;
    fireEvent.input(firstStart, { target: { value: "2025-11-30" } });
    fireEvent.input(firstStart, { target: { value: original } });
    fireEvent.click(initial.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(launcher).toHaveFocus());
    expect(journal.editDormancy).not.toHaveBeenCalled();
    fireEvent.click(launcher);
    const history = within(screen.getByRole("dialog", { name: "Dormancy" }));
    const starts = history.getAllByLabelText("Start", { exact: true });
    const ends = history.getAllByLabelText<HTMLInputElement>("End", { exact: true });
    expect(starts).toHaveLength(10);
    expect(history.getAllByRole("listitem")[0]).toHaveTextContent("Ongoing");
    expect(ends.map((end) => end.value)).toEqual(
      Array.from({ length: 9 }, (_, i) => `2025-${String(11 - i).padStart(2, "0")}-02`),
    );
    const newestStart = starts[1];
    const oldestEnd = ends[8];
    if (newestStart === undefined || oldestEnd === undefined)
      throw new Error("Expected editable dates for ten periods");
    fireEvent.input(newestStart, { target: { value: "2025-10-31" } });
    fireEvent.input(oldestEnd, { target: { value: "2025-03-03" } });
    const closed = { ...ongoing, endedAt: instant("2025-12-02T12:00:00Z") };
    journal.editDormancy.mockResolvedValueOnce({ kind: "read", periods: [...periods, closed] });
    fireEvent.click(history.getByRole("button", { name: "End dormancy" }));
    fireEvent.click(
      within(screen.getByRole("alertdialog")).getByRole("button", { name: "End dormancy" }),
    );
    const start = await history.findByRole("button", { name: "Start dormancy" });
    journal.editDormancy.mockResolvedValueOnce({
      kind: "read",
      periods: [
        ...periods,
        closed,
        { ...ongoing, id: "new", startedAt: instant("2026-01-01T12:00:00Z") },
      ],
    });
    fireEvent.click(start);
    fireEvent.click(
      within(screen.getByRole("alertdialog")).getByRole("button", { name: "Start dormancy" }),
    );
    await history.findByRole("button", { name: "End dormancy" });
    expect(history.getAllByRole("listitem")).toHaveLength(10);
    expect(history.getAllByLabelText("End", { exact: true }).at(-1)).toHaveValue("2025-03-03");
    journal.editDormancy.mockClear();
    fireEvent.click(history.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(launcher).toHaveFocus());
    expect(journal.editDormancy).toHaveBeenCalledExactlyOnceWith("p1", {
      kind: "correctDates",
      periods: [
        { ...periods[10], startedAt: "2025-10-31T12:00:00.000Z" },
        { ...periods[2], endedAt: "2025-03-03T12:00:00.000Z" },
      ],
    });
  });

  it("should show each plant with its three most recent operations, oldest first", async () => {
    const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
    const pesticides = new Set([neemId]);
    const actions = new Set(["watered", "pesticide"] as const);
    const treated = JournalFixtures.care({
      id: "o4",
      date: "2026-04-04T22:30:00Z",
      moisture: "wet",
      maybeNote: "Recovered",
      actions,
      pesticides,
    });
    const moderateCare = JournalFixtures.care({
      id: "o2",
      date: "2026-02-02T00:00:00Z",
      moisture: "moderatePlus",
      actions: new Set(),
    });
    const repot = JournalFixtures.repot("o3", "2026-03-03T00:00:00Z");
    const pesticideCatalog = [
      {
        id: neemId,
        data: {
          name: pesticideName("Neem oil"),
          type: "insecticide" as const,
          maybeInfo: null,
        },
        status: "active" as const,
      },
    ];
    const operationWindows: {
      plantId: PlantId;
      window: OperationWindow;
    }[] = [];
    const monsteraUnavailableAttention: AttentionSample = {
      plant: JournalFixtures.monstera().id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    };
    const ficusRedAlertAttention: AttentionSample = {
      plant: JournalFixtures.ficus().id,
      watering: {
        kind: "redAlert",
        sampleCount: 5,
        averageInterval: milliseconds("86400000"),
        elapsed: milliseconds("176400000"),
      },
    };
    const browsingAttention: Journal.AttentionProjection = {
      measuredAt: instant("2026-01-01T00:00:00Z"),
      plants: [monsteraUnavailableAttention, ficusRedAlertAttention],
    };
    const journal = JournalFixtures.buildJournal({
      attentionProjection: browsingAttention,
      getPlantsResults: [
        { kind: "read", plants: [JournalFixtures.monstera(), JournalFixtures.ficus()] },
      ],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage([treated, repot, moderateCare], true)],
        p2: [JournalFixtures.operationsPage()],
      },
      getPesticidesResult: { kind: "read", entries: pesticideCatalog },
      operationWindows,
    });

    renderJournal(journal);

    const card = await screen.findByRole("article", { name: "Fern" });
    expect(within(card).getByText("Ficus lyrata")).toBeInTheDocument();
    expect(within(card).getByText("Balcony")).toBeInTheDocument();
    const [perliteInSummary] = within(card).getAllByText("Perlite 100%");
    expect(perliteInSummary).toBeInTheDocument();
    expect(within(card).getByRole("list", { name: "Recent operations" })).toBeInTheDocument();
    const renderedOperations = within(card).getAllByRole("listitem");
    expect(renderedOperations.map((operation) => operation.textContent)).toEqual([
      "2nd of February02.02CareMoistureModerate +ActionsNone recorded",
      "3rd of March03.03RepotSubstratePerlite 100%",
      "5th of April05.04CareMoistureWetActionsNeem oilWatered, PesticidePesticidesNeem oilRecovered",
    ]);
    const recentDate = within(card).getByText("5th of April").closest("time");
    expect(recentDate).toHaveAttribute("datetime", "2026-04-04T22:30:00Z");
    const plantNames = screen
      .getAllByRole("article")
      .map((article) => article.getAttribute("aria-label"));
    expect(plantNames).toEqual(["Monstera deliciosa", "Fern"]);
    expect(operationWindows).toEqual([
      { plantId: "p2", window: { offset: 0, size: 3 } },
      { plantId: "p1", window: { offset: 0, size: 3 } },
    ]);
    const emptyCard = screen.getByRole("article", { name: "Monstera deliciosa" });
    expect(within(emptyCard).getByText("No operations yet.")).toBeInTheDocument();

    fireEvent.click(within(card).getByRole("button", { name: "Show operation history" }));
    const history = await within(card).findByRole("table");
    expect(operationWindows.at(-1)).toEqual({
      plantId: "p1",
      window: { offset: 3, size: 10 },
    });
    fireEvent.click(
      within(history).getByRole("button", {
        name: "Edit historical care operation 1 from 05.04.2026",
      }),
    );
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
  });

  it("should render backend attention in presentation order", async () => {
    const unknownPlant = {
      ...JournalFixtures.ficus(),
      id: plantId("unknown"),
      details: {
        ...JournalFixtures.ficus().details,
        maybeNickname: nickname("Unknown"),
      },
    };
    const unknownUnavailableAttention: AttentionSample = {
      plant: unknownPlant.id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    };
    const urgentPlant = {
      ...JournalFixtures.ficus(),
      id: plantId("urgent"),
      details: {
        ...JournalFixtures.ficus().details,
        maybeNickname: nickname("Urgent"),
      },
    };
    const urgentRedAlertAttention: AttentionSample = {
      plant: urgentPlant.id,
      watering: {
        kind: "redAlert",
        sampleCount: 5,
        averageInterval: milliseconds("0"),
        elapsed: milliseconds("1"),
      },
    };
    const recentlyWateredPlant = {
      ...JournalFixtures.ficus(),
      id: plantId("current"),
      details: {
        ...JournalFixtures.ficus().details,
        maybeNickname: nickname("Current"),
      },
    };
    const recentlyWateredCurrentAttention: AttentionSample = {
      plant: recentlyWateredPlant.id,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: milliseconds("10"),
        elapsed: milliseconds("1"),
      },
    };
    const browsingAttention: Journal.AttentionProjection = {
      measuredAt: instant("2026-01-01T00:00:00Z"),
      plants: [
        recentlyWateredCurrentAttention,
        urgentRedAlertAttention,
        unknownUnavailableAttention,
      ],
    };
    const journal = JournalFixtures.buildJournal({
      attentionProjection: browsingAttention,
      getPlantsResults: [
        { kind: "read", plants: [unknownPlant, urgentPlant, recentlyWateredPlant] },
      ],
      getOperationsByPlantId: Object.fromEntries(
        [unknownPlant, urgentPlant, recentlyWateredPlant].map(({ id }) => [
          id,
          [JournalFixtures.operationsPage()],
        ]),
      ),
    });

    renderJournal(journal);

    const articles = await screen.findAllByRole("article");
    expect(articles.map((article) => article.getAttribute("aria-label"))).toEqual([
      "Unknown",
      "Urgent",
      "Current",
    ]);
  });

  it("should reorder cards when a pushed attention projection changes urgency", async () => {
    const ficusId = JournalFixtures.ficus().id;
    const monsteraId = JournalFixtures.monstera().id;
    const initialAttention: Journal.AttentionProjection = {
      measuredAt: instant("2026-01-01T00:00:00Z"),
      plants: [
        {
          plant: ficusId,
          watering: {
            kind: "current",
            sampleCount: 5,
            averageInterval: milliseconds("86400000"),
            elapsed: milliseconds("43200000"),
          },
        },
        {
          plant: monsteraId,
          watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
        },
      ],
    };
    const journal = JournalFixtures.buildJournal({
      attentionProjection: initialAttention,
      getPlantsResults: [
        { kind: "read", plants: [JournalFixtures.ficus(), JournalFixtures.monstera()] },
      ],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });
    renderJournal(journal);
    const initialCards = await screen.findAllByRole("article");
    expect(initialCards.map((c) => c.getAttribute("aria-label"))).toEqual([
      "Monstera deliciosa",
      "Fern",
    ]);

    journal.pushAttention({
      measuredAt: instant("2026-01-02T00:00:00Z"),
      plants: [
        {
          plant: ficusId,
          watering: {
            kind: "redAlert",
            sampleCount: 5,
            averageInterval: milliseconds("86400000"),
            elapsed: milliseconds("172800000"),
          },
        },
        {
          plant: monsteraId,
          watering: {
            kind: "current",
            sampleCount: 5,
            averageInterval: milliseconds("86400000"),
            elapsed: milliseconds("43200000"),
          },
        },
      ],
    });

    await waitFor(() => {
      expect(screen.getAllByRole("article").map((c) => c.getAttribute("aria-label"))).toEqual([
        "Fern",
        "Monstera deliciosa",
      ]);
    });
  });

  it("should identify a journal containing one active plant", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });

    renderJournal(journal);

    const heading = await screen.findByRole("heading", { name: "Plant Journal" });
    const garden = await screen.findByRole("button", { name: /Garden.*1 plant/ });
    const cemetery = screen.getByRole("button", { name: /Cemetery.*0 plants/ });

    expect(heading).toBeInTheDocument();
    expect(screen.queryByText("Care history, growing conditions, and repotting notes.")).toBeNull();
    expect(garden).toHaveAttribute("aria-pressed", "true");
    expect(cemetery).toHaveAttribute("aria-pressed", "false");
  });

  it("should open and close the photos sheet for an active plant", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });

    renderJournal(journal);

    const control = await screen.findByRole("button", { name: "Photos for Fern" });
    fireEvent.click(control);

    const dialog = await screen.findByRole("dialog", { name: "Photos for Fern" });
    expect(dialog).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Close photos for Fern" }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Photos for Fern" })).toBeNull();
    });
    expect(control).toHaveFocus();
  });

  it("should open the photos sheet for an archived plant from the cemetery", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });

    renderJournal(journal);

    fireEvent.click(await screen.findByRole("button", { name: /Cemetery/ }));
    const control = await screen.findByRole("button", { name: "Photos for Fern" });
    fireEvent.click(control);

    expect(await screen.findByRole("dialog", { name: "Photos for Fern" })).toBeInTheDocument();
  });

  it("should select the cemetery from its focusable control", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    renderJournal(journal);
    const cemetery = await screen.findByRole("button", { name: /Cemetery.*0 plants/ });

    cemetery.focus();
    fireEvent.click(cemetery);
    const cemeteryView = await screen.findByRole("region", { name: "Cemetery" });
    const garden = screen.getByRole("button", { name: /Garden.*1 plant/ });

    expect(cemeteryView).toBeInTheDocument();
    expect(cemetery).toHaveFocus();
    expect(cemetery).toHaveAttribute("aria-pressed", "true");
    expect(garden).toHaveAttribute("aria-pressed", "false");
  });

  it("should restore the cemetery from its URL after refresh and respond to navigation", async () => {
    window.history.replaceState(null, "", "/?from=bookmark#journal");
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const mounted = renderJournal(journal);
    await screen.findByRole("region", { name: "Garden" });

    fireEvent.click(screen.getByRole("button", { name: /Cemetery/ }));
    await screen.findByRole("region", { name: "Cemetery" });
    const cemeterySearch = window.location.search;
    const cemeteryHash = window.location.hash;

    mounted.unmount();
    renderJournal(journal);
    const restoredCemetery = await screen.findByRole("region", { name: "Cemetery" });
    const selectedCemetery = screen.getByRole("button", { name: /Cemetery/ });
    const restoredCemeteryLabel = restoredCemetery.getAttribute("aria-label");
    const cemeterySelected = selectedCemetery.getAttribute("aria-pressed");

    window.history.replaceState(null, "", "/?from=bookmark#journal");
    window.dispatchEvent(new PopStateEvent("popstate"));
    const restoredGarden = await screen.findByRole("region", { name: "Garden" });

    expect(cemeterySearch).toBe("?from=bookmark&view=cemetery");
    expect(cemeteryHash).toBe("#journal");
    expect(restoredCemeteryLabel).toBe("Cemetery");
    expect(cemeterySelected).toBe("true");
    expect(restoredGarden).toBeInTheDocument();
    expect(window.location.search).toBe("?from=bookmark");
  });

  it("should report an unavailable cemetery when its bookmarked URL cannot load", async () => {
    window.history.replaceState(null, "", "/?view=cemetery");
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived" ? Promise.reject(new Error("offline")) : base.getPlants(),
    };

    renderJournal(journal);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    const selectedCemetery = screen.getByRole("button", { name: /Cemetery/ });
    expect(selectedCemetery).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("article", { name: "Fern" })).toBeNull();
  });

  it("should load archived plants only when the cemetery is opened", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const firstOperation = {
      ...JournalFixtures.care({ id: "first", date: "2026-02-01T10:00:00Z", moisture: "dry" }),
      plant: archivedPlant.id,
    };
    const latestOperation = {
      ...JournalFixtures.care({ id: "last", date: "2026-04-03T18:00:00Z", moisture: "wet" }),
      plant: archivedPlant.id,
    };
    const base = JournalFixtures.buildJournalWithCemetery(
      [JournalFixtures.ficus()],
      [archivedPlant],
      {
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: {
          p1: [JournalFixtures.operationsPage()],
          p2: [
            JournalFixtures.operationsPage([latestOperation], true),
            JournalFixtures.operationsPage([firstOperation]),
          ],
        },
      },
    );
    const statuses: (string | undefined)[] = [];
    const getOperationDates = vi.fn(() =>
      Promise.resolve({
        kind: "read" as const,
        dates: {
          kind: "recorded" as const,
          first: firstOperation.date,
          last: latestOperation.date,
        },
      }),
    );
    const journal = {
      ...base,
      getPlants: (status?: "active" | "archived") => {
        statuses.push(status);
        return base.getPlants(status);
      },
      getArchivedCount: vi.fn(() => Promise.resolve({ kind: "read" as const, count: 2 })),
      getOperationDates,
    };

    renderJournal(journal);

    const cemetery = await screen.findByRole("button", { name: /Cemetery.*2 plants/ });
    const unopenedStatuses = [...statuses];
    fireEvent.click(cemetery);
    const card = await screen.findByRole("article", { name: "Monstera deliciosa" });
    fireEvent.click(within(card).getByRole("button", { name: "Show operation history" }));
    const history = await within(card).findByRole("table");

    const life = within(card).getByRole("complementary", { name: /Recorded care dates/ });
    const editOperation = within(card).getByRole("button", { name: /Edit recent care operation/ });

    expect(unopenedStatuses).toEqual([undefined]);
    expect(screen.getByRole("button", { name: /Cemetery.*1 plant/ })).toBeInTheDocument();
    expect(within(life).getByText("RIP")).toBeInTheDocument();
    expect(within(life).getByText("01.02.2026")).toBeInTheDocument();
    expect(within(life).getByText("03.04.2026")).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: /Log operation/ })).toBeNull();
    expect(editOperation).toBeInTheDocument();
    expect(history).toHaveTextContent("01.02.2026");
    expect(getOperationDates).toHaveBeenCalledWith(archivedPlant.id);
    expect(statuses).toEqual([undefined, "archived"]);
  });

  it("should refresh the cemetery list when returning from the garden", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const base = JournalFixtures.buildJournalWithCemetery(
      [JournalFixtures.ficus()],
      [archivedPlant],
      {
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: {
          p1: [JournalFixtures.operationsPage()],
          p2: [JournalFixtures.operationsPage()],
        },
      },
    );
    const statuses: (string | undefined)[] = [];
    const journal = {
      ...base,
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      getPlants: (status?: "active" | "archived") => {
        statuses.push(status);
        return base.getPlants(status);
      },
    };
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));
    await screen.findByRole("article", { name: "Monstera deliciosa" });
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));
    const cemeteryCard = await screen.findByRole("article", { name: "Monstera deliciosa" });
    const readsBeforeReselect = statuses.length;
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));

    expect(cemeteryCard).toBeInTheDocument();
    expect(statuses).toEqual([undefined, "archived", "archived"]);
    expect(statuses).toHaveLength(readsBeforeReselect);
  });

  it("should fail an archived date read instead of showing a partial cemetery", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const getOperationDates = vi.fn(() =>
      Promise.resolve({
        kind: "readFailed" as const,
        reason: new Error("private details"),
      }),
    );
    const journal = {
      ...JournalFixtures.buildJournalWithCemetery([JournalFixtures.ficus()], [archivedPlant], {
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: {
          p1: [JournalFixtures.operationsPage()],
          p2: [JournalFixtures.operationsPage()],
        },
      }),
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      getOperationDates,
    };
    renderJournal(journal);
    const cemetery = await screen.findByRole("button", { name: /Cemetery.*1 plant/ });

    fireEvent.click(cemetery);
    const alert = await screen.findByRole("alert");
    const incompleteCard = screen.queryByRole("article", { name: "Monstera deliciosa" });
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    const gardenCard = await screen.findByRole("article", { name: "Fern" });

    expect(alert).toHaveTextContent("The journal could not be loaded.");
    expect(incompleteCard).toBeNull();
    expect(getOperationDates).toHaveBeenCalledWith(archivedPlant.id);
    expect(screen.queryByText("private details")).toBeNull();
    expect(gardenCard).toBeInTheDocument();
  });

  it("should report an unreadable cemetery list rather than showing partial cards", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived"
          ? Promise.resolve({ kind: "readFailed" as const, reason: new Error("offline") })
          : base.getPlants(),
    };
    renderJournal(journal);
    const cemetery = await screen.findByRole("button", { name: /Cemetery.*0 plants/ });

    fireEvent.click(cemetery);
    const alert = await screen.findByRole("alert");

    expect(alert).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByRole("article", { name: "Fern" })).toBeNull();
  });

  it("should leave a restored garden visible after an obsolete cemetery read rejects", async () => {
    let rejectArchived: (reason: Error) => void = () => undefined;
    const archivedRequest = new Promise<GetPlantsResult>((_resolve, reject) => {
      rejectArchived = reject;
    });
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) => (status === "archived" ? archivedRequest : base.getPlants()),
    };
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    rejectArchived(new Error("stale request"));
    await archivedRequest.catch(() => undefined);
    await Promise.resolve();

    expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
  });

  it("should ignore obsolete cemetery list and history responses after returning to the garden", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    let finishList: (result: GetPlantsResult) => void = () => undefined;
    const pendingList = new Promise<GetPlantsResult>((resolve) => {
      finishList = resolve;
    });
    let finishHistory: (result: GetOperationsResult) => void = () => undefined;
    const pendingHistory = new Promise<GetOperationsResult>((resolve) => {
      finishHistory = resolve;
    });
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const getOperationDates = vi.fn(() =>
      Promise.resolve({ kind: "read" as const, dates: { kind: "empty" as const } }),
    );
    let cemeteryReads = 0;
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status !== "archived"
          ? base.getPlants()
          : cemeteryReads++ === 0
            ? pendingList
            : Promise.resolve({ kind: "read" as const, plants: [archivedPlant] }),
      getOperationDates,
      getOperations: (id: PlantId, window: OperationWindow) =>
        id === archivedPlant.id ? pendingHistory : base.getOperations(id, window),
    };
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    finishList({ kind: "read", plants: [archivedPlant] });
    await pendingList;
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    await vi.waitFor(() => {
      expect(getOperationDates).toHaveBeenCalledOnce();
    });
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    finishHistory(JournalFixtures.operationsPage());
    await pendingHistory;
    await Promise.resolve();

    expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("should show a failed archived count read instead of a guessed number", async () => {
    const journal = {
      ...JournalFixtures.buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      }),
      getArchivedCount: () =>
        Promise.resolve({
          kind: "readFailed" as const,
          reason: new Error("private details"),
        }),
    };

    renderJournal(journal);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByRole("button", { name: /Cemetery.*0/ })).toBeNull();
  });

  it("should show a pending indicator for an active plant not in the received attention projection", async () => {
    const missing = JournalFixtures.monstera();
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [{ kind: "read", plants: [JournalFixtures.ficus(), missing] }],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });

    renderJournal(journal);

    const newCard = await screen.findByRole("article", { name: "Monstera deliciosa" });
    expect(
      within(newCard).getByRole("complementary", {
        name: "Attention pending for Monstera deliciosa",
      }),
    ).toBeInTheDocument();
    expect(within(newCard).getByText("No operations yet.")).toBeInTheDocument();
  });

  it("should show the attention feed connection state indicator", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });

    renderJournal(journal);

    await screen.findByRole("article", { name: "Fern" });
    const indicator = screen.getByRole("status", { name: "Backend connected" });
    expect(indicator).toBeInTheDocument();
    expect(screen.getByText(/Just updated|Updated \d+s ago/)).toBeInTheDocument();

    journal.setAttentionConnection("disconnected");
    expect(await screen.findByRole("status", { name: "Backend disconnected" })).toBeInTheDocument();
  });

  it("should create a plant from the cemetery without logging care or measuring attention", async () => {
    const added = JournalFixtures.monstera();
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [
        { kind: "read", plants: [JournalFixtures.ficus()] },
        { kind: "read", plants: [JournalFixtures.ficus(), added] },
      ],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });
    const createPlant = vi
      .fn<(_details: NewPlantDetails) => Promise<Journal.CreatePlantResult>>()
      .mockResolvedValue({ kind: "created", plant: added });
    const logOperation = vi.fn(
      (id: PlantId, date: Journal.Instant, details: Journal.OperationDetails) =>
        base.logOperation(id, date, details),
    );
    const journal = {
      ...base,
      createPlant,
      logOperation,
      getPlants: (status?: "active" | "archived") =>
        status === "archived"
          ? Promise.resolve({ kind: "read" as const, plants: [] })
          : base.getPlants(),
    };

    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: "  Monstera deliciosa  " },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: " Kitchen " },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    const newCard = await screen.findByRole("article", { name: "Monstera deliciosa" });
    expect(
      within(newCard).getByRole("complementary", {
        name: "Attention pending for Monstera deliciosa",
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Garden.*2 plants/ })).toHaveFocus();
    expect(createPlant).toHaveBeenCalledOnce();
    expect(createPlant.mock.calls[0]?.[0]).toMatchObject({
      species: "Monstera deliciosa",
      location: "Kitchen",
      substrate: [{ share: 100 }],
    });
    expect(logOperation).not.toHaveBeenCalled();
  });

  it("should keep invalid and failed plant creation editable without duplicating a saved plant", async () => {
    const added = JournalFixtures.monstera();
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [
        { kind: "read", plants: [JournalFixtures.ficus()] },
        { kind: "readFailed", reason: new Error("offline") },
      ],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const createPlant = vi
      .fn()
      .mockResolvedValueOnce({ kind: "createFailed", reason: new Error("offline") })
      .mockResolvedValueOnce({ kind: "created", plant: added });
    const journal = { ...base, createPlant };

    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Enter a species.");
    expect(createPlant).not.toHaveBeenCalled();
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: "Monstera deliciosa" },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Kitchen" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));
    expect(await within(dialog).findByText("The plant could not be saved.")).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: "Species" })).toHaveValue(
      "Monstera deliciosa",
    );
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    await vi.waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Plant was saved, but the garden could not be loaded.",
      );
    });
    expect(screen.queryByRole("dialog", { name: "Plant editor" })).not.toBeInTheDocument();
    expect(createPlant).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: /Garden.*1 plant/ })).toHaveFocus();
  });

  it("should keep the plant sheet open when plant creation is rejected", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      createPlant: () => Promise.reject(new Error("private connection")),
    };
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: "Aloe" },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Office" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "The plant could not be saved.",
    );
    expect(within(dialog).getByRole("textbox", { name: "Species" })).toHaveValue("Aloe");
    expect(dialog).toHaveFocus();
    expect(screen.queryByText("private connection")).not.toBeInTheDocument();
  });

  it.each([
    {
      mode: "create" as const,
      openEditor: () => fireEvent.click(screen.getByRole("button", { name: "Add plant" })),
      expectedFocus: "Add plant",
    },
    {
      mode: "edit" as const,
      openEditor: () => fireEvent.click(screen.getByRole("button", { name: "Edit Fern" })),
      expectedFocus: "Edit Fern",
    },
  ])(
    "should cancel plant $mode without a write and return focus to the trigger",
    async ({ openEditor, expectedFocus }) => {
      const base = JournalFixtures.buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      });
      const createPlant = vi.fn((details: NewPlantDetails) => base.createPlant(details));
      const editPlant = vi.fn((id: PlantId, details: NewPlantDetails) =>
        base.editPlant(id, details),
      );
      const journal = { ...base, createPlant, editPlant };
      renderJournal(journal);
      await screen.findByRole("article", { name: "Fern" });
      openEditor();
      fireEvent.click(screen.getByRole("button", { name: "Collapse plant editor" }));

      await vi.waitFor(() => {
        expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
      });
      expect(screen.getByRole("button", { name: expectedFocus })).toHaveFocus();
      expect(createPlant).not.toHaveBeenCalled();
      expect(editPlant).not.toHaveBeenCalled();
    },
  );

  it("should show saved substrate mixes in the plant editor and collapse it on Escape", async () => {
    const existingMix: Journal.SubstrateMix = {
      id: Journal.substrateMixId("00000000-0000-4000-8000-000000000013"),
      name: Journal.substrateMixName("Bark mix"),
      maybeNotes: null,
      substrate: Journal.substrate([
        {
          component: Journal.substrateComponentId("00000000-0000-4000-8000-000000000004"),
          share: Journal.percentage(100),
        },
      ]),
    };
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      getSubstrateMixesResult: { kind: "read", entries: [existingMix] },
    });
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    fireEvent.click(screen.getByRole("button", { name: "Load saved mix" }));
    expect(screen.getByRole("dialog", { name: "Load substrate mix" })).toBeInTheDocument();
    expect(screen.getByText("Bark mix")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });
    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Load substrate mix" })).toBeNull();
    });
    fireEvent.keyDown(window, { key: "Escape" });
    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
    });
  });

  it("should edit an active plant's details without logging an operation and refocus the edit control", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const editPlant = vi
      .fn<(_id: PlantId, _details: NewPlantDetails) => Promise<Journal.EditPlantResult>>()
      .mockResolvedValue({ kind: "edited" });
    const journal = { ...base, editPlant };
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });
    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    expect(within(dialog).getByRole("heading", { name: "Edit plant" })).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: "Species" })).toHaveValue("Ficus lyrata");
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Living room" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
    });
    expect(editPlant).toHaveBeenCalledOnce();
    expect(editPlant.mock.calls[0]?.[0]).toEqual(JournalFixtures.ficus().id);
    expect(editPlant.mock.calls[0]?.[1]).toMatchObject({ location: "Living room" });
    expect(screen.getByRole("button", { name: "Edit Fern" })).toHaveFocus();
  });

  it("should report an edited plant whose garden reload rejects", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    let gardenReads = 0;
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived"
          ? base.getPlants()
          : gardenReads++ === 0
            ? base.getPlants()
            : Promise.reject(new Error("private details")),
      editPlant: () => Promise.resolve({ kind: "edited" as const }),
    };
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });
    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await vi.waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    });
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it.each([
    [{ kind: "plantMissing" as const }, "This plant no longer exists."],
    [{ kind: "plantArchived" as const }, "This plant is archived and can no longer be edited."],
    [{ kind: "unknownComponent" as const }, "Choose known substrate components."],
    [
      { kind: "catalogReadFailed" as const, reason: new Error("private catalog") },
      "The substrate catalog could not be read. Try again.",
    ],
    [
      { kind: "editFailed" as const, reason: new Error("private write") },
      "The plant could not be saved.",
    ],
    [new Error("private connection"), "The plant could not be saved."],
  ])("should keep the plant sheet open when editing reports %o", async (outcome, message) => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      editPlant: () =>
        outcome instanceof Error ? Promise.reject(outcome) : Promise.resolve(outcome),
    };
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });
    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });

    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
  });

  it("should fail the journal load when current plants cannot be read or pushed attention is unmatched", async () => {
    const unrelatedSample = {
      plant: JournalFixtures.monstera().id,
      watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
    };
    const mismatchedAttention: Journal.AttentionProjection = {
      ...unavailableFicusAttention,
      plants: [...unavailableFicusAttention.plants, unrelatedSample],
    };
    const ficusSample: Journal.AttentionSample = {
      plant: JournalFixtures.ficus().id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    };
    const duplicateAttention: Journal.AttentionProjection = {
      ...unavailableFicusAttention,
      plants: [ficusSample, ficusSample],
    };
    const cases = [
      {
        plants: { kind: "readFailed" as const, reason: new Error("private plant details") },
        attentionProjection: unavailableFicusAttention,
      },
      {
        plants: { kind: "read" as const, plants: [JournalFixtures.ficus()] },
        attentionProjection: mismatchedAttention,
      },
      {
        plants: { kind: "read" as const, plants: [JournalFixtures.ficus()] },
        attentionProjection: duplicateAttention,
      },
    ];
    for (const { plants, attentionProjection } of cases) {
      const journal = JournalFixtures.buildJournal({
        attentionProjection,
        getPlantsResults: [plants],
      });
      const view = renderJournal(journal);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("The journal could not be loaded.");
      expect(screen.queryByRole("article")).not.toBeInTheDocument();
      view.unmount();
    }
  });

  it("should fail when a pushed projection contains a plant outside the active set", async () => {
    const ficusPlant = JournalFixtures.ficus();
    const monsteraPlant = JournalFixtures.monstera();
    const ficusOnlyProjection: Journal.AttentionProjection = {
      measuredAt: Journal.instant("2026-01-01T00:00:00Z"),
      plants: [
        {
          plant: ficusPlant.id,
          watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
        },
      ],
    };
    const journal = JournalFixtures.buildJournal({
      attentionProjection: ficusOnlyProjection,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });

    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });

    journal.pushAttention({
      measuredAt: Journal.instant("2026-01-02T00:00:00Z"),
      plants: [
        {
          plant: ficusPlant.id,
          watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
        },
        {
          plant: monsteraPlant.id,
          watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
        },
      ],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
  });

  it("should remove archived plant ids from the recently-archived set when a projection omits them", async () => {
    const ficusPlant = JournalFixtures.ficus();
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [
        { kind: "read", plants: [ficusPlant] },
        { kind: "read", plants: [] },
      ],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      archivePlant: () => Promise.resolve({ kind: "archived" as const }),
    };

    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));
    fireEvent.click(screen.getByRole("button", { name: "Move to cemetery" }));
    fireEvent.click(
      within(screen.getByRole("alertdialog", { name: "Move Fern to cemetery" })).getByRole(
        "button",
        {
          name: "Move to cemetery",
        },
      ),
    );
    await screen.findByRole("button", { name: /Garden.*0 plants/ });

    journal.pushAttention({ measuredAt: Journal.instant("2026-01-02T00:00:00Z"), plants: [] });

    expect(screen.queryByRole("alert")).toBeNull();
  });

  it.each([
    [
      "operation history readFailed",
      () =>
        JournalFixtures.buildJournal({
          attentionProjection: unavailableFicusAttention,
          getOperationsByPlantId: {
            p1: [{ kind: "readFailed" as const, reason: new Error("private details") }],
          },
        }),
    ],
    [
      "rejected getPlants",
      () => {
        const base = JournalFixtures.buildJournal();
        return { ...base, getPlants: () => Promise.reject(new Error("private details")) };
      },
    ],
    [
      "substrate catalog failure",
      () =>
        JournalFixtures.buildJournal({
          getSubstrateComponentsResult: {
            kind: "readFailed" as const,
            reason: new Error("private details"),
          },
        }),
    ],
    [
      "pesticide catalog failure",
      () =>
        JournalFixtures.buildJournal({
          getPesticidesResult: {
            kind: "readFailed" as const,
            reason: new Error("private details"),
          },
        }),
    ],
  ])(
    "should fail the journal load on %s without showing the error reason",
    async (_label, makeJournal) => {
      renderJournal(makeJournal());
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "The journal could not be loaded.",
      );
      expect(screen.queryByText("private details")).not.toBeInTheDocument();
    },
  );

  it("should report a saved plant whose garden reload rejects", async () => {
    const added = JournalFixtures.monstera();
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [{ kind: "read", plants: [JournalFixtures.ficus()] }],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    let gardenReads = 0;
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived"
          ? base.getPlants()
          : gardenReads++ === 0
            ? base.getPlants()
            : Promise.reject(new Error("private details")),
      createPlant: () => Promise.resolve({ kind: "created" as const, plant: added }),
    };
    renderJournal(journal);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: "Monstera deliciosa" },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Kitchen" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    await vi.waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Plant was saved, but the garden could not be loaded.",
      );
    });
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });
});
