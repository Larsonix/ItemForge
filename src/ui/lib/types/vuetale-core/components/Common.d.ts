/**
 * Simplified type declarations for Vuetale Common components.
 *
 * Derived from the actual Vuetale Common.d.ts and Common.js source.
 * See docs/VUETALE_API_VERIFIED.md for full verification against library source.
 *
 * These types cover the subset ItemForge uses. The real Common.d.ts imports
 * from 'vuetale/types/elements' which isn't available outside Vuetale's build.
 */
import { DefineComponent, PublicProps, SlotsType, VNode } from 'vue'

// ── Hytale UI Types ──────────────────────────────────────────────────────

interface Anchor {
  Full?: number
  Width?: number
  Height?: number
  Horizontal?: number
  Vertical?: number
  Top?: number
  Bottom?: number
  Left?: number
  Right?: number
  MaxWidth?: number
  MinWidth?: number
}

interface Padding {
  Full?: number
  Horizontal?: number
  Vertical?: number
  Top?: number
  Bottom?: number
  Left?: number
  Right?: number
}

interface PatchStyle {
  TexturePath?: string
  Color?: string
  Border?: number
  HorizontalBorder?: number
  VerticalBorder?: number
}

interface LabelStyle {
  FontSize?: number
  TextColor?: string
  RenderBold?: boolean
  RenderUppercase?: boolean
  HorizontalAlignment?: string
  VerticalAlignment?: string
  FontName?: string
  LetterSpacing?: number
  Wrap?: boolean
}

interface ScrollbarStyle {
  Spacing?: number
  Size?: number
  Background?: PatchStyle
  Handle?: PatchStyle
  HoveredHandle?: PatchStyle
  DraggedHandle?: PatchStyle
  OnlyVisibleWhenHovered?: boolean
}

interface TextTooltipStyle {
  Background?: PatchStyle
  MaxWidth?: number
  LabelStyle?: LabelStyle
  Padding?: Padding
}

/** NumberField format constraints */
interface NumberFieldFormat {
  MinValue?: number
  MaxValue?: number
  Step?: number
  MaxDecimalPlaces?: number
  DefaultValue?: number
}

/** Base props shared by all native Hytale elements */
interface BaseProps {
  anchor?: Anchor
  autoScrollDown?: boolean
  background?: PatchStyle | string
  contentHeight?: number
  contentWidth?: number
  flexWeight?: number
  hitTestVisible?: boolean
  keepScrollPosition?: boolean
  maskTexturePath?: string
  outlineColor?: string
  outlineSize?: number
  overscroll?: boolean
  padding?: Padding
  textTooltipShowDelay?: number
  textTooltipStyle?: TextTooltipStyle
  tooltipText?: string
  visible?: boolean
}

// ── Component Helper Type ────────────────────────────────────────────────

type C<P, S extends Record<string, (...args: any[]) => VNode[]> = Record<never, never>> =
  DefineComponent<P, {}, {}, {}, {}, {}, {}, {}, string, PublicProps, Readonly<P>, {}, SlotsType<S>>

// ── Vars (style defaults) ────────────────────────────────────────────────

export declare const Vars: {
  DefaultLabelStyle: () => LabelStyle
  DefaultScrollbarStyle: () => ScrollbarStyle
  DefaultExtraSpacingScrollbarStyle: () => ScrollbarStyle
  DefaultCheckBoxStyle: () => object
  DefaultInputFieldStyle: () => object
  DefaultDropdownBoxStyle: () => object
  DefaultTextTooltipStyle: () => TextTooltipStyle
  ContentPadding: () => Padding
  TitleStyle: () => LabelStyle
  SubtitleStyle: () => LabelStyle
}

// ── Common Components ────────────────────────────────────────────────────

// Button types — all consume sounds/anchor/text, forward rest to native TextButton
type TextButtonProps = {
  sounds?: object
  anchor?: Anchor
  text?: unknown
  disabled?: boolean
  onActivating?: (...args: any[]) => void
  onRightClicking?: (...args: any[]) => void
  onMouseEntered?: (...args: any[]) => void
  onMouseExited?: (...args: any[]) => void
  [key: string]: any  // forwards all native TextButton props
}

type ButtonProps = {
  sounds?: object
  anchor?: Anchor
  defaultSquareButtonStyle?: unknown
  disabled?: boolean
  onActivating?: (...args: any[]) => void
  [key: string]: any
}

// Container types
type ContainerSlots = {
  closeButton?: () => VNode[]
  content?: () => VNode[]
  title?: () => VNode[]
}

type ContainerProps = {
  contentPadding?: Padding
  closeButton?: boolean
  anchor?: Anchor
  [key: string]: any  // forwards to outer Group
}

// Input types — consume anchor, forward rest to native element
type InputWrapperProps = {
  anchor?: Anchor
  [key: string]: any  // forwards all native element props including events
}

export declare const Common: {
  // ── Buttons ──
  TextButton: C<TextButtonProps>
  SecondaryTextButton: C<TextButtonProps>
  SmallSecondaryTextButton: C<TextButtonProps>
  SmallTertiaryTextButton: C<TextButtonProps>
  TertiaryTextButton: C<TextButtonProps>
  CancelTextButton: C<TextButtonProps>
  Button: C<ButtonProps>
  SecondaryButton: C<ButtonProps>
  TertiaryButton: C<ButtonProps>
  CancelButton: C<ButtonProps>
  HeaderTextButton: C<BaseProps & { onActivating?: (...args: any[]) => void; [key: string]: any }>

  // ── Close Button (positioned: Top:-8, Right:-8, 32x32) ──
  CloseButton: C<BaseProps & {
    disabled?: boolean
    onActivating?: (...args: any[]) => void
    [key: string]: any
  }>

  // ── Inputs ──
  CheckBox: C<BaseProps & {
    value?: boolean
    disabled?: boolean
    onValueChanged?: (...args: any[]) => void
    [key: string]: any
  }>

  CheckBoxWithLabel: C<BaseProps & {
    checked?: boolean
    text?: unknown
    [key: string]: any
  }>

  TextField: C<InputWrapperProps>
  NumberField: C<InputWrapperProps>
  DropdownBox: C<InputWrapperProps>

  // ── Layout ──
  ContentSeparator: C<{ anchor?: Anchor; [key: string]: any }>

  // ── Labels ──
  Title: C<{ text?: string; alignment?: string; [key: string]: any }>
  Subtitle: C<{ text?: unknown; [key: string]: any }>
  TitleLabel: C<BaseProps & { text?: string; [key: string]: any }>
  PanelTitle: C<{ text?: string; alignment?: string; [key: string]: any }>

  // ── Containers ──
  Panel: C<BaseProps>
  Container: C<ContainerProps, ContainerSlots>
  DecoratedContainer: C<ContainerProps, ContainerSlots>
  PageOverlay: C<BaseProps>
  BackButton: C<BaseProps>

  // ── Misc ──
  DefaultSpinner: C<InputWrapperProps>
  ActionButtonContainer: C<BaseProps>
  ActionButtonSeparator: C<BaseProps>
  VerticalActionButtonSeparator: C<BaseProps>
  HeaderSeparator: C<BaseProps>
  HeaderSearch: C<BaseProps & { marginRight?: number }>
  VerticalSeparator: C<BaseProps>
  PanelSeparatorFancy: C<{ anchor?: Anchor; [key: string]: any }>
}
