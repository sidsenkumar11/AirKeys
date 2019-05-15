from keras.models import load_model
from PIL import Image, ImageFilter
import numpy as np

def imageprepare(argv):
    """
    This function returns the pixel values.
    The imput is a png file location.
    """
    im = Image.open(argv).convert('L')
    width = float(im.size[0])
    height = float(im.size[1])
    newImage = Image.new('L', (28, 28), (255))  # creates white canvas of 28x28 pixels

    if width > height:  # check which dimension is bigger
        # Width is bigger. Width becomes 20 pixels.
        nheight = int(round((20.0 / width * height), 0))  # resize height according to ratio width
        if (nheight == 0):  # rare case but minimum is 1 pixel
            nheight = 1
            # resize and sharpen
        img = im.resize((20, nheight), Image.ANTIALIAS).filter(ImageFilter.SHARPEN)
        wtop = int(round(((28 - nheight) / 2), 0))  # calculate horizontal position
        newImage.paste(img, (4, wtop))  # paste resized image on white canvas
    else:
        # Height is bigger. Heigth becomes 20 pixels.
        nwidth = int(round((20.0 / height * width), 0))  # resize width according to ratio height
        if (nwidth == 0):  # rare case but minimum is 1 pixel
            nwidth = 1
            # resize and sharpen
        img = im.resize((nwidth, 20), Image.ANTIALIAS).filter(ImageFilter.SHARPEN)
        wleft = int(round(((28 - nwidth) / 2), 0))  # caculate vertical pozition
        newImage.paste(img, (wleft, 4))  # paste resized image on white canvas

    # newImage.save("sample.png

    tv = list(newImage.getdata())  # get pixel values

    # normalize pixels to 0 and 1. 0 is pure white, 1 is pure black.
    tva = [(255 - x) * 1.0 / 255.0 for x in tv]
    # print(tva)
    return tva


def model_image(image):
  x = [imageprepare(image)]
  newArr=[[0 for d in range(28)] for y in range(28)]
  k = 0
  for i in range(28):
    for j in range(28):
      newArr[i][j]=x[0][k]
      k=k+1
        
  newArr = np.array(newArr)
  return newArr

def preload_model(name):
  model = load_model(name)
  return model

def resolve_prediction(prediction):
  mappping = {}
  for i in xrange(1,27):
    mapping[i] = chr(64+i)
  for i in xrange(27, 36):
    mapping[i] = char(48 + i - 26)
  mapping[36] = '0'
  return mapping[prediction] 

def predict_from_model(test_image):
  pred = model.predict(test_image.reshape(1, 28, 28, 1))
  return resolve_prediction(pred.argmax())

def convert_image(image):
  if(len(sys.argv) == 2):
    test_image = model_image(image)
    return test_image
  else:
    print('Insuffient input.')
  