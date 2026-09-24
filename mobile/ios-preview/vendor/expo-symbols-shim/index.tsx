import React from 'react';
import { Text, type ColorValue, type StyleProp, type TextStyle } from 'react-native';

type SymbolName = string | { ios?: string; android?: string; web?: string };

type Props = {
  name: SymbolName;
  size?: number;
  tintColor?: ColorValue;
  weight?: string;
  style?: StyleProp<TextStyle>;
};

const glyphs: Record<string, string> = {
  'message.fill': '✉︎',
  'dot.radiowaves.left.and.right': '◉',
  network: '⌬',
  'sos.circle.fill': '!',
  'gearshape.fill': '⚙︎',
  'antenna.radiowaves.left.and.right': '◉',
  wifi: '⌁',
  'point.3.connected.trianglepath.dotted': '⌬',
  'network.badge.shield.half.filled': '◎',
  'arrow.up': '↑',
};

export function SymbolView({ name, size = 24, tintColor = '#FFFFFF', weight = 'regular', style }: Props) {
  const key = typeof name === 'string' ? name : name.ios ?? name.android ?? name.web ?? '';
  return (
    <Text
      accessibilityElementsHidden
      style={[
        {
          color: tintColor,
          fontSize: size,
          lineHeight: size + 3,
          fontWeight: weight === 'bold' || weight === 'semibold' ? '700' : '600',
          textAlign: 'center',
          minWidth: size + 4,
        },
        style,
      ]}
    >
      {glyphs[key] ?? '•'}
    </Text>
  );
}
