import QRCode from 'qrcode';

const value = new URLSearchParams(window.location.search).get('value');
const canvas = document.getElementById('qr');

if (value && canvas) {
  QRCode.toCanvas(canvas, value, {
    errorCorrectionLevel: 'M',
    margin: 2,
    width: 220,
    color: { dark: '#08111f', light: '#ffffff' }
  });
}
