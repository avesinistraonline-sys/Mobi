# Ronco Mobi no Android Auto

## Como testar no carro
1. Compile o APK como sempre (GitHub Actions) e instale no celular.
2. Abra o app **uma vez no celular** e conceda a permissão de localização.
3. No celular: Configurações > Android Auto > role até **Versão** e toque 10 vezes > menu ⋮ > **Configurações do desenvolvedor** > ative **Fontes desconhecidas**.
   (Sem isso o Android Auto não mostra apps instalados fora da Play Store.)
4. Conecte ao carro e abra **Ronco Mobi** no launcher do multimídia.
   Se não aparecer: Android Auto > Personalizar o launcher > marque o Ronco Mobi.

## O que tem na tela do carro
- Velocímetro em tela cheia (km/h, marcha, rpm do som em arco).
- Botões: **LIGAR/PARAR**, **Ajustes**, **Vol −**, **Vol +**.
- **Ajustes**: Volume, Motor (tipo, aspiração, agressividade, estouros, só aspiração, alívio),
  Camadas do som, Estéreo e saída de áudio, Perfis e câmbio (incl. modo teste).
- O Android Auto não permite sliders. Cada ajuste numérico abre uma tela com barra + botões **−** e **+**;
  o botão **Passo ×1/×2/×5/×10** aumenta o tamanho de cada toque.

## Som só no alto-falante esquerdo
O sintetizador sempre gerou estéreo de verdade, então a causa mais provável é o caminho de áudio do
carro/Android Auto. Para diagnosticar e contornar:
1. Ajustes > Estéreo e saída de áudio > **Testar canais** (toca bipe esquerdo, direito e ambos).
2. Se o bipe direito não sair, mude **Saída de áudio** para **Mono duplicado** (mesmo sinal nos dois canais).
   Também existe no app do celular (seção "Saída de áudio").
