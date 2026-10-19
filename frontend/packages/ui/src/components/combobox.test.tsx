import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { i18n } from "@jabiz/client";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { expectAccessible } from "@jabiz/ui/testing";
import { Combobox, type ComboboxOption } from "./combobox";

const countries: ComboboxOption[] = [
  { value: "JP", label: "Japan" },
  { value: "CN", label: "China", description: "People’s Republic" },
  { value: "US", label: "United States" },
];

describe("Combobox", () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage("en"));
  });

  it("is a named combobox that opens a list of options and picks one", async () => {
    function Field() {
      const [value, setValue] = useState<string | null>(null);
      return (
        <>
          <label htmlFor="country">Country</label>
          <Combobox
            id="country"
            value={value}
            onChange={setValue}
            options={countries}
            required
          />
          <output>{String(value)}</output>
        </>
      );
    }
    render(<Field />);
    const trigger = screen.getByRole("combobox", { name: "Country" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveAttribute("aria-required", "true");
    expect(trigger).toHaveTextContent("Select…");
    await userEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getAllByRole("option")).toHaveLength(3);
    await expectAccessible();

    // Typing filters by label (and description); Enter picks the highlighted option.
    await userEvent.keyboard("chin");
    expect(screen.getAllByRole("option")).toHaveLength(1);
    await userEvent.keyboard("{Enter}");
    expect(screen.getByRole("status")).toHaveTextContent("CN");
    expect(trigger).toHaveTextContent("China");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  it("says when nothing matches, and can be cleared", async () => {
    const onChange = vi.fn();
    render(
      <Combobox
        aria-label="Country"
        value="JP"
        onChange={onChange}
        options={countries}
        clearable
        invalid
      />,
    );
    expect(screen.getByRole("combobox", { name: "Country" })).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    await userEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(onChange).toHaveBeenCalledWith(null);
    // The clear button goes with the value: focus stays in the field rather than falling to the page.
    expect(document.activeElement).toBe(
      screen.getByRole("combobox", { name: "Country" }),
    );
    await userEvent.click(screen.getByRole("combobox", { name: "Country" }));
    await userEvent.keyboard("zzz");
    expect(screen.getByText("No matches")).toBeInTheDocument();
  });

  it("in remote mode, asks the caller for options and shows the chosen value by its label", async () => {
    const onSearch = vi.fn();
    const { rerender } = render(
      <Combobox
        aria-label="Carrier"
        value="c-9"
        selectedLabel="Zulu Freight"
        onChange={() => {}}
        options={[]}
        onSearch={onSearch}
        loading
      />,
    );
    const trigger = screen.getByRole("combobox", { name: "Carrier" });
    expect(trigger).toHaveTextContent("Zulu Freight");
    await userEvent.click(trigger);
    expect(screen.getByRole("status")).toHaveTextContent("Loading…");
    await userEvent.keyboard("alp");
    expect(onSearch).toHaveBeenLastCalledWith("alp");

    // The server's answer is shown as it is: not filtered again here.
    rerender(
      <Combobox
        aria-label="Carrier"
        value="c-9"
        selectedLabel="Zulu Freight"
        onChange={() => {}}
        options={[{ value: "c-1", label: "Bravo Lines" }]}
        onSearch={onSearch}
      />,
    );
    expect(
      screen.getByRole("option", { name: "Bravo Lines" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("listbox")).toHaveAttribute("aria-busy", "false");
    expect(screen.getByRole("status")).toHaveTextContent("Results: 1");
    await userEvent.keyboard("{Escape}");
    // Closing forgets the search.
    expect(onSearch).toHaveBeenLastCalledWith("");
  });

  it("while loading, marks the list busy, keeps its options unpickable and announces in one live region", async () => {
    const onChange = vi.fn();
    const remote = (options: ComboboxOption[], loading: boolean) => (
      <Combobox
        aria-label="Carrier"
        value={null}
        onChange={onChange}
        options={options}
        onSearch={() => {}}
        loading={loading}
      />
    );
    const { rerender } = render(remote([], true));
    await userEvent.click(screen.getByRole("combobox", { name: "Carrier" }));
    const list = screen.getByRole("listbox");
    expect(list).toHaveAttribute("aria-busy", "true");
    const status = screen.getByRole("status");
    expect(status).toHaveTextContent("Loading…");
    expect(list).not.toContainElement(status);
    expect(screen.queryByText("No matches")).not.toBeInTheDocument();
    await expectAccessible();

    // Searching again: the options already there stay, but cannot be picked until the new answer replaces them.
    const shown = [{ value: "c-1", label: "Bravo Lines" }];
    rerender(remote(shown, true));
    const stale = screen.getByRole("option", { name: "Bravo Lines" });
    expect(stale).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(stale);
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("listbox")).toHaveAttribute("aria-busy", "true");
    await expectAccessible();

    // The same live region (not a new one) now says how many results arrived.
    rerender(remote(shown, false));
    expect(screen.getByRole("listbox")).toHaveAttribute("aria-busy", "false");
    expect(screen.getByRole("status")).toBe(status);
    expect(status).toHaveTextContent("Results: 1");
    await userEvent.click(screen.getByRole("option", { name: "Bravo Lines" }));
    expect(onChange).toHaveBeenCalledWith("c-1");
  });
});
